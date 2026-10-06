package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.Cursors;
import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.springframework.stereotype.Service;
import process.pipeline.PipelineDefinition;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The customer API's pipelines and starting a run (MIG-332, ADR-025; OpenAPI listPipelines, getPipeline, startRun).
 *
 * <ul>
 *   <li>GET /v1/pipelines: the workspace's pipelines, newest first, cursor paging; scope pipelines:read.</li>
 *   <li>GET /v1/pipelines/{id}: one, with its input contract (the JSON Schema a started run's record must meet), whether it
 *   takes files, who reviews its runs and the event types its runs send; scope pipelines:read.</li>
 *   <li>POST /v1/pipelines/{id}/runs: a run with a record and/or file ids. The record is checked against the input
 *   contract first and nothing is stored when it fails (422, each field's path); the files must be the workspace's own;
 *   then the intake is written and the run queued (RunIntake). 202 with the Run and its Location. Idempotent per client;
 *   scope runs:write.</li>
 * </ul>
 * Another workspace's pipeline answers exactly as one that does not exist (404).
 */
@Service
public class CustomerPipelines {

    /** What the API's run events are; a pipeline with a review adds the review's. */
    static final List<String> RUN_EVENTS = Collections.unmodifiableList(Arrays.asList("run.started", "run.completed", "run.failed"));
    static final List<String> REVIEW_EVENTS = Collections.unmodifiableList(Arrays.asList("run.review.requested", "run.review.decided"));
    /** MIG-333: the steps whose files a run lists (run_output), each announced as file.available. */
    static final List<String> FILE_TASKS = Collections.unmodifiableList(Arrays.asList("save_file", "render_pdf", "upload_bucket"));
    /** The largest record a run takes, in bytes of JSON (ADR-025 decision 7: a JSON body is at most 1 MB). */
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final int MAX_REFERENCE = 128;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PipelineCatalogue catalogue;
    private final InputContracts contracts;
    private final RunFiles files;
    private final RunIntake intake;
    private final Idempotency idempotency;

    public CustomerPipelines(PipelineCatalogue catalogue, InputContracts contracts, RunFiles files, RunIntake intake, Idempotency idempotency) {
        this.catalogue = catalogue;
        this.contracts = contracts;
        this.files = files;
        this.intake = intake;
        this.idempotency = idempotency;
    }

    public CustomerAnswer list(Integer limit, String cursor) {
        String instance = "/v1/pipelines";
        if (!TenantContext.hasScope(ApiScopes.PIPELINES_READ)) {
            return CustomerAnswer.problem(insufficient(ApiScopes.PIPELINES_READ), instance);
        }
        int size;
        Long after;
        try {
            size = Cursors.limit(limit);
            after = Cursors.lastId(cursor);
        } catch (IllegalArgumentException bad) {
            return CustomerAnswer.problem(Problem.of(400, bad.getMessage()), instance);
        }
        long tenantId = TenantContext.getTenantId();
        List<PipelineCatalogue.Entry> page = this.catalogue.page(tenantId, after, size + 1);
        boolean more = page.size() > size;
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Optional<InputContracts.Checked>> seen = new LinkedHashMap<>();
        try {
            for (PipelineCatalogue.Entry entry : more ? page.subList(0, size) : page) {
                data.add(this.view(entry, seen));
            }
        } catch (InputContracts.Unavailable unavailable) {
            return CustomerAnswer.problem(unavailableContracts(), instance);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("nextCursor", more ? Cursors.after(page.get(size - 1).jobId) : null);
        return CustomerAnswer.of(200, body, null);
    }

    public CustomerAnswer get(String pipelineId) {
        String instance = "/v1/pipelines/" + pipelineId;
        if (!TenantContext.hasScope(ApiScopes.PIPELINES_READ)) {
            return CustomerAnswer.problem(insufficient(ApiScopes.PIPELINES_READ), instance);
        }
        Optional<PipelineCatalogue.Entry> entry = this.catalogue.find(TenantContext.getTenantId(), pipelineId);
        if (!entry.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, "No such pipeline."), instance);
        }
        try {
            return CustomerAnswer.of(200, this.view(entry.get(), new LinkedHashMap<>()), null);
        } catch (InputContracts.Unavailable unavailable) {
            return CustomerAnswer.problem(unavailableContracts(), instance);
        }
    }

    /** POST /v1/pipelines/{id}/runs with the raw body, so the request's fingerprint is exactly what was sent. */
    public CustomerAnswer startRun(String pipelineId, byte[] body, String idempotencyKey) {
        String instance = "/v1/pipelines/" + pipelineId + "/runs";
        if (!TenantContext.hasScope(ApiScopes.RUNS_WRITE)) {
            return CustomerAnswer.problem(insufficient(ApiScopes.RUNS_WRITE), instance);
        }
        byte[] sent = body == null ? new byte[0] : body;
        if (sent.length > MAX_BODY_BYTES) {
            return CustomerAnswer.problem(Problem.of(413, "A run's request is at most 1 MB of JSON; send large data as files."), instance);
        }
        String fingerprint = IdempotencyKeys.fingerprint("POST".getBytes(StandardCharsets.UTF_8), instance.getBytes(StandardCharsets.UTF_8), sent);
        return this.idempotency.once(idempotencyKey, instance, fingerprint, () -> this.start(pipelineId, sent, instance));
    }

    private CustomerAnswer start(String pipelineId, byte[] body, String instance) {
        long tenantId = TenantContext.getTenantId();
        Optional<PipelineCatalogue.Entry> found = this.catalogue.find(tenantId, pipelineId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, "No such pipeline."), instance);
        }
        PipelineCatalogue.Entry pipeline = found.get();
        JsonNode request;
        try {
            request = body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException unreadable) {
            return CustomerAnswer.problem(Problem.of(400, "The request is not JSON."), instance);
        }
        if (request == null || !request.isObject()) {
            return CustomerAnswer.problem(Problem.of(400, "The request is a JSON object: {record, files, reference}."), instance);
        }
        List<Problem.FieldError> errors = new ArrayList<>();
        JsonNode record = request.get("record");
        if (record != null && !record.isNull() && !record.isObject()) {
            errors.add(new Problem.FieldError("record", "must be an object"));
        }
        JsonNode reference = request.get("reference");
        if (reference != null && !reference.isNull() && (!reference.isTextual() || reference.asText().length() > MAX_REFERENCE)) {
            errors.add(new Problem.FieldError("reference", "must be text of at most " + MAX_REFERENCE + " characters"));
        }
        JsonNode fileList = request.get("files");
        List<String> ids = new ArrayList<>();
        if (fileList != null && !fileList.isNull()) {
            if (!fileList.isArray()) {
                errors.add(new Problem.FieldError("files", "must be a list of file ids"));
            } else if (fileList.size() > RunFiles.MAX_FILES) {
                errors.add(new Problem.FieldError("files", "at most " + RunFiles.MAX_FILES + " files a run"));
            } else {
                ids = RunFiles.idsOf(fileList);
            }
        }
        if (!errors.isEmpty()) {
            return CustomerAnswer.problem(Problem.validation("The request does not say what the run needs.", errors), instance);
        }
        JsonNode theRecord = record == null || record.isNull() ? null : record;
        Optional<PipelineDefinition.ContractRef> contract = pipeline.inputContract();
        if (contract.isPresent()) {
            InputContracts.Checked checked;
            try {
                checked = this.contracts.check(tenantId, contract.get(), theRecord == null ? JSON.createObjectNode() : theRecord, "record");
            } catch (InputContracts.Unavailable unavailable) {
                return CustomerAnswer.problem(unavailableContracts(), instance);
            }
            if (theRecord == null) {
                errors.add(new Problem.FieldError("record", "this pipeline's runs take a record meeting its input contract"));
            }
            if (Boolean.FALSE.equals(checked.valid)) {
                errors.addAll(checked.errors);
            }
            if (!errors.isEmpty()) {
                return CustomerAnswer.problem(Problem.validation("The record does not meet the pipeline's input contract.", errors), instance);
            }
        }
        List<Map<String, Object>> named = new ArrayList<>();
        if (!ids.isEmpty()) {
            if (pipeline.definition == null) {
                return CustomerAnswer.problem(Problem.validation("This pipeline takes no files.", Collections.singletonList(
                    new Problem.FieldError("files", "this pipeline takes no files"))), instance);
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String id : ids) {
                if (id != null) {
                    unique.add(id);
                }
            }
            Map<String, Map<String, Object>> foundFiles = this.files.of(tenantId, unique);
            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> file = ids.get(i) == null ? null : foundFiles.get(ids.get(i));
                if (file == null) {
                    errors.add(new Problem.FieldError("files[" + i + "]", "no such file"));
                } else {
                    named.add(file);
                }
            }
            if (!errors.isEmpty()) {
                return CustomerAnswer.problem(Problem.validation("A named file is not there.", errors), instance);
            }
        }
        RunIntake.Start start = new RunIntake.Start();
        start.record = theRecord;
        start.files = named;
        start.reference = reference == null || reference.isNull() ? null : reference.asText();
        start.clientId = TenantContext.getClientId();
        start.origin = "a run started";
        RunIntake.Outcome outcome = this.intake.start(pipeline, start);
        if (outcome.refusal != null) {
            return CustomerAnswer.problem(outcome.refusal, instance);
        }
        return CustomerAnswer.of(202, outcome.run, "/v1/runs/" + outcome.run.get("id"));
    }

    /** The Pipeline schema. The input contract is read once per contract for a page. */
    private Map<String, Object> view(PipelineCatalogue.Entry entry, Map<String, Optional<InputContracts.Checked>> seen) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(entry.jobId));
        view.put("name", entry.name);
        view.put("description", null);
        Optional<PipelineDefinition.ContractRef> contract = entry.inputContract();
        JsonNode schema = null;
        if (contract.isPresent()) {
            String key = contract.get().getContractId() + "|" + contract.get().getContractName() + "|" + contract.get().getVersion();
            Optional<InputContracts.Checked> checked = seen.get(key);
            if (checked == null) {
                checked = Optional.of(this.contracts.check(entry.tenantId, contract.get(), null, "record"));
                seen.put(key, checked);
            }
            schema = checked.get().schema;
        }
        view.put("inputContract", schema);
        view.put("acceptsFiles", entry.definition != null);
        List<String> review = entry.review();
        view.put("review", review);
        List<String> emits = new ArrayList<>(RUN_EVENTS);
        if (!review.isEmpty()) {
            emits.addAll(REVIEW_EVENTS);
        }
        if (makesFiles(entry.definition)) {
            emits.add(CustomerEventTypes.FILE_AVAILABLE);
        }
        view.put("emits", emits);
        return view;
    }

    /** Whether a run of this definition makes a file a customer can download (a step of {@link #FILE_TASKS}). */
    static boolean makesFiles(PipelineDefinition definition) {
        if (definition == null || definition.getSteps() == null) {
            return false;
        }
        for (PipelineDefinition.Step step : definition.getSteps()) {
            if (step != null && FILE_TASKS.contains(step.getTask())) {
                return true;
            }
        }
        return false;
    }

    static Problem insufficient(String scope) {
        return Problem.of(403, "This token does not hold the scope " + scope + ".").kind("insufficient-scope");
    }

    static Problem unavailableContracts() {
        return Problem.of(503, "The pipeline's input contract cannot be read right now. Try again in a moment.");
    }
}
