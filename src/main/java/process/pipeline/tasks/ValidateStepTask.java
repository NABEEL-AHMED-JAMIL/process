package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.ContractChecker;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validate (MIG-231): each input row against a data contract, by integration-service (the contract and its rules are
 * its). On invalid rows: {@code fail} (the default) fails the step and names the first; {@code drop} passes only the
 * valid rows on; {@code flag} passes every row with {@code _valid} and {@code _errors} beside it. Rows go in batches
 * of {@value ContractChecker#BATCH}; an error names a path and a rule, never a value.
 */
@Component
public class ValidateStepTask extends RegisteredTask {

    static final String VALID = "_valid";
    static final String ERRORS = "_errors";

    static final TaskSpec SPEC = TaskSpec.builder("validate", "Validate", TaskKind.PROCESS)
        .description("Checks each row against a data contract (integration-service): fail, drop or flag the rows that do not hold.")
        .input(TaskSpec.rows("The rows to check, each as the contract's payload."))
        .output(TaskSpec.rows("fail/drop: the valid rows. flag: every row, with _valid and _errors."))
        .config(JsonSchema.object()
            .property("contractId", JsonSchema.integer().minimum(1).title("Contract").format("data-contract")
                .description("One of the workspace's data contracts; or name a system contract below."))
            .property("contractName", JsonSchema.string().maxLength(255).title("Contract name")
                .description("A contract by name: the workspace's, else a system contract."))
            .property("version", JsonSchema.integer().minimum(1).title("Contract version")
                .description("A version to hold to; empty for the contract's active version."))
            .property("onInvalid", JsonSchema.string().enumOf("fail", "drop", "flag").title("Rows that do not hold").defaultValue("fail")
                .description("fail the step, drop the rows, or flag them (_valid, _errors) and go on.")))
        .backing(TaskSpec.INTEGRATION)
        .retry(2, 10)
        .timeoutSeconds(300)
        .aiToolName("validate_rows")
        .build();

    private final ContractChecker contracts;

    public ValidateStepTask(ContractChecker contracts) {
        super(SPEC);
        this.contracts = contracts;
    }

    @Override
    public Optional<String> unavailable() {
        return this.contracts.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        boolean byId = config.get("contractId") != null;
        boolean byName = Configs.text(config, "contractName", null) != null;
        if (byId == byName) {
            return Collections.singletonList(new DefinitionProblem("contractId", "name the contract by its id or by its name, not both"));
        }
        return Collections.emptyList();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String onInvalid = Configs.text(config, "onInvalid", "fail");
        Dataset input = context.input();
        List<ContractChecker.RowVerdict> verdicts = new ArrayList<>(input.size());
        String contract = null;
        for (int from = 0; from < input.size(); from += ContractChecker.BATCH) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Validate was stopped.");
            }
            List<Map<String, Object>> batch = input.getRows().subList(from, Math.min(input.size(), from + ContractChecker.BATCH));
            ContractChecker.ContractCall call = new ContractChecker.ContractCall();
            call.tenantId = context.tenantId();
            call.jobQueueId = context.jobQueueId();
            call.stepKey = context.stepKey();
            call.contractId = Configs.longValue(config, "contractId");
            call.contractName = Configs.text(config, "contractName", null);
            call.version = Configs.integer(config, "version", null);
            call.rows = batch;
            ContractChecker.ContractVerdicts answer = this.contracts.validate(call);
            contract = String.format("%s v%s", answer.name == null ? "the contract" : answer.name, answer.version == null ? "?" : answer.version);
            Map<Integer, ContractChecker.RowVerdict> byIndex = new LinkedHashMap<>();
            for (ContractChecker.RowVerdict verdict : answer.rows == null ? Collections.<ContractChecker.RowVerdict>emptyList() : answer.rows) {
                byIndex.put(verdict.index, verdict);
            }
            for (int i = 0; i < batch.size(); i++) {
                ContractChecker.RowVerdict verdict = byIndex.get(i);
                if (verdict == null) {
                    throw new IllegalStateException(String.format("integration-service gave no verdict for row %d.", from + i + 1));
                }
                verdicts.add(verdict);
            }
        }
        int invalid = 0;
        int first = -1;
        for (int i = 0; i < verdicts.size(); i++) {
            if (!verdicts.get(i).valid) {
                invalid++;
                first = first < 0 ? i : first;
            }
        }
        context.log(String.format("%d of %d row(s) hold to %s.", input.size() - invalid, input.size(), contract == null ? "the contract" : contract));
        if (invalid > 0 && "fail".equals(onInvalid)) {
            throw new IllegalStateException(String.format("%d of %d row(s) do not hold to %s; row %d: %s", invalid, input.size(), contract,
                first + 1, String.join("; ", firstErrors(verdicts.get(first)))));
        }
        List<String> columns = new ArrayList<>(input.getColumns());
        List<Map<String, Object>> rows = new ArrayList<>();
        if ("flag".equals(onInvalid)) {
            columns.remove(VALID);
            columns.remove(ERRORS);
            columns.add(VALID);
            columns.add(ERRORS);
        }
        for (int i = 0; i < input.size(); i++) {
            ContractChecker.RowVerdict verdict = verdicts.get(i);
            if ("flag".equals(onInvalid)) {
                Map<String, Object> row = new LinkedHashMap<>(input.getRows().get(i));
                row.put(VALID, verdict.valid);
                row.put(ERRORS, verdict.valid ? null : String.join("; ", verdict.errors == null ? Collections.<String>emptyList() : verdict.errors));
                rows.add(row);
            } else if (verdict.valid) {
                rows.add(input.getRows().get(i));
            }
        }
        if (invalid > 0 && "drop".equals(onInvalid)) {
            context.warn(String.format("%d row(s) dropped: they do not hold to %s.", invalid, contract));
        }
        return StepResult.of(new Dataset(columns, rows));
    }

    private static List<String> firstErrors(ContractChecker.RowVerdict verdict) {
        List<String> errors = verdict.errors == null ? Collections.<String>emptyList() : verdict.errors;
        return errors.size() > 3 ? errors.subList(0, 3) : errors;
    }
}
