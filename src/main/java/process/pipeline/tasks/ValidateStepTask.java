package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.ContractChecker;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
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
 *
 * MIG-344: streamed -- a batch is checked and written out before the next is read, so it holds one batch.
 */
@Component
public class ValidateStepTask extends RegisteredTask implements StreamingStepTask {

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
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String onInvalid = Configs.text(config, "onInvalid", "fail");
        Checked checked = new Checked(context, config, onInvalid);
        try (RowSource input = context.openInput()) {
            List<String> columns = new ArrayList<>(input.columns());
            if ("flag".equals(onInvalid)) {
                columns.remove(VALID);
                columns.remove(ERRORS);
                columns.add(VALID);
                columns.add(ERRORS);
            }
            context.output().declare(columns);
            List<Map<String, Object>> pending = new ArrayList<>(ContractChecker.BATCH);
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                for (Map<String, Object> row : batch) {
                    pending.add(row);
                    if (pending.size() == ContractChecker.BATCH) {
                        checked.check(pending);
                        pending = new ArrayList<>(ContractChecker.BATCH);
                    }
                }
            }
            if (!pending.isEmpty()) {
                checked.check(pending);
            }
        }
        long total = checked.rows;
        String contract = checked.contract;
        context.log(String.format("%d of %d row(s) hold to %s.", total - checked.invalid, total, contract == null ? "the contract" : contract));
        if (checked.invalid > 0 && "fail".equals(onInvalid)) {
            throw new IllegalStateException(String.format("%d of %d row(s) do not hold to %s; row %d: %s", checked.invalid, total, contract,
                checked.first + 1, String.join("; ", firstErrors(checked.firstVerdict))));
        }
        if (checked.invalid > 0 && "drop".equals(onInvalid)) {
            context.warn(String.format("%d row(s) dropped: they do not hold to %s.", checked.invalid, contract));
        }
        return StepResult.streamed(context.output().size());
    }

    /** The rows checked so far: a batch at a time to integration-service, each written out as its verdict says. */
    private final class Checked {
        private final StreamContext context;
        private final Map<String, Object> config;
        private final String onInvalid;
        private final RowSink out;
        long rows;
        long invalid;
        long first = -1;
        ContractChecker.RowVerdict firstVerdict;
        String contract;

        Checked(StreamContext context, Map<String, Object> config, String onInvalid) {
            this.context = context;
            this.config = config;
            this.onInvalid = onInvalid;
            this.out = context.output();
        }

        void check(List<Map<String, Object>> batch) throws Exception {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Validate was stopped.");
            }
            ContractChecker.ContractCall call = new ContractChecker.ContractCall();
            call.tenantId = this.context.tenantId();
            call.jobQueueId = this.context.jobQueueId();
            call.stepKey = this.context.stepKey();
            call.contractId = Configs.longValue(this.config, "contractId");
            call.contractName = Configs.text(this.config, "contractName", null);
            call.version = Configs.integer(this.config, "version", null);
            call.rows = batch;
            ContractChecker.ContractVerdicts answer = contracts.validate(call);
            this.contract = String.format("%s v%s", answer.name == null ? "the contract" : answer.name, answer.version == null ? "?" : answer.version);
            Map<Integer, ContractChecker.RowVerdict> byIndex = new LinkedHashMap<>();
            for (ContractChecker.RowVerdict verdict : answer.rows == null ? Collections.<ContractChecker.RowVerdict>emptyList() : answer.rows) {
                byIndex.put(verdict.index, verdict);
            }
            for (int i = 0; i < batch.size(); i++) {
                if (byIndex.get(i) == null) {
                    throw new IllegalStateException(String.format("integration-service gave no verdict for row %d.", this.rows + i + 1));
                }
            }
            for (int i = 0; i < batch.size(); i++) {
                ContractChecker.RowVerdict verdict = byIndex.get(i);
                if (!verdict.valid) {
                    if (this.first < 0) {
                        this.first = this.rows + i;
                        this.firstVerdict = verdict;
                    }
                    this.invalid++;
                }
                if ("flag".equals(this.onInvalid)) {
                    Map<String, Object> row = new LinkedHashMap<>(batch.get(i));
                    row.put(VALID, verdict.valid);
                    row.put(ERRORS, verdict.valid ? null : String.join("; ", verdict.errors == null ? Collections.<String>emptyList() : verdict.errors));
                    this.out.add(row);
                } else if (verdict.valid) {
                    this.out.add(batch.get(i));
                }
            }
            this.rows += batch.size();
        }
    }

    private static List<String> firstErrors(ContractChecker.RowVerdict verdict) {
        List<String> errors = verdict.errors == null ? Collections.<String>emptyList() : verdict.errors;
        return errors.size() > 3 ? errors.subList(0, 3) : errors;
    }
}
