package process.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.engine.OneRunInFlight;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.service.SourceJobService;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.PipelineOutputFields;
import process.pipeline.RunResults;
import process.pipeline.StepStore;
import process.pipeline.items.WorkspaceMasks;
import process.security.TenantContext;
import process.slo.RunSlo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A workflow's run_pipeline step (workflow-service, MIG-273): start a job of a workspace, service to service. No person
 * is signed in -- an approval three steps back, or a timer, is what reached this step -- so the request carries the
 * shared X-Internal-Token and names the workspace; the job is run as that workspace with every rule Run now applies
 * (active, the workspace's own, nothing in flight, the workspace not paused), and the run's audit log says which
 * workflow instance and step asked. The gateway never exposes /internal.
 */
@RestController
@RequestMapping("/internal/workflows")
public class InternalWorkflowRunRestApi {

    /** The role the run is requested with: the workspace itself, as an administrator would Run now. */
    static final String WORKSPACE_ROLE = "TENANT_ADMIN";

    private final Logger logger = LoggerFactory.getLogger(InternalWorkflowRunRestApi.class);
    private final SourceJobService jobs;
    private final JobQueueRepository runs;
    private final byte[] token;
    /** MIG-424: a finished run's answer, the masks it is read with, and a pipeline's parameters and output fields. */
    private final RunResults results;
    private final WorkspaceMasks masks;
    private final PipelineOutputFields fields;
    private final StepStore steps;
    private final PipelineDefinitionStore definitions;

    public InternalWorkflowRunRestApi(SourceJobService jobs, JobQueueRepository runs, @Value("${internal.service-token:}") String token,
        RunResults results, WorkspaceMasks masks, PipelineOutputFields fields, StepStore steps, PipelineDefinitionStore definitions) {
        this.jobs = jobs;
        this.runs = runs;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
        this.results = results;
        this.masks = masks;
        this.fields = fields;
        this.steps = steps;
        this.definitions = definitions;
    }

    /** What the step asks to run. */
    public static class RunRequest {
        private Long tenantId;
        private Long jobId;
        private Long instanceId;
        private String stepKey;
        /** MIG-424: the run's parameter values by name, as the step mapped them; null or empty for the pipeline's defaults. */
        private Map<String, Object> parameters;

        public Map<String, Object> getParameters() { return parameters; }

        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

        public Long getTenantId() { return tenantId; }

        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

        public Long getJobId() { return jobId; }

        public void setJobId(Long jobId) { this.jobId = jobId; }

        public Long getInstanceId() { return instanceId; }

        public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }

        public String getStepKey() { return stepKey; }

        public void setStepKey(String stepKey) { this.stepKey = stepKey; }
    }

    /** Queues the run; the answer's data is its id, or Run now's refusal in words. */
    @PostMapping(value = "/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> run(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody RunRequest request) throws Exception {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getJobId() == null) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId) and the job (jobId)."));
        }
        TenantContext.set(request.getTenantId(), WORKSPACE_ROLE, null, "workflow-service");
        try {
            String reason = String.format("Queued by workflow instance %s, step %s.", request.getInstanceId(), request.getStepKey());
            ResponseDto answer;
            try {
                // V220: the run goes beside the job's own runs, one in flight per job and request.
                answer = this.jobs.runSourceJobFor(request.getJobId(), request.getInstanceId(), request.getStepKey(), reason,
                    request.getParameters());
            } catch (RuntimeException refused) {
                if (request.getInstanceId() != null && OneRunInFlight.isWorkflowViolation(refused)) {
                    // The request asked twice (a retried call): its run is the one already in flight.
                    Long running = this.runs.findInFlightWorkflowRun(request.getJobId(), request.getInstanceId(), request.getTenantId());
                    if (running != null) {
                        return ResponseEntity.ok(new ResponseDto("SUCCESS", "The request's run of this job is already in flight.", running));
                    }
                }
                if (OneRunInFlight.isViolation(refused) || OneRunInFlight.isWorkflowViolation(refused)) {
                    return ResponseEntity.ok(new ResponseDto("ERROR", "A run of this job is already in flight."));
                }
                throw refused;
            }
            this.logger.info("Workflow instance {} step {} asked to run job {} of workspace {}: {}", request.getInstanceId(),
                request.getStepKey(), request.getJobId(), request.getTenantId(), answer.getMessage());
            return ResponseEntity.ok(answer);
        } finally {
            TenantContext.clear();
        }
    }

    /** Which run a waiting step asks about. */
    public static class StateRequest {
        private Long tenantId;
        private Long runId;
        /** MIG-424: what to take from the run's output once it has completed ({from, take, fields, maxRows}); null for nothing. */
        private JsonNode results;

        public Long getTenantId() { return tenantId; }

        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

        public Long getRunId() { return runId; }

        public void setRunId(Long runId) { this.runId = runId; }

        public JsonNode getResults() { return results; }

        public void setResults(JsonNode results) { this.results = results; }
    }

    /**
     * 2026-10-21: a run_pipeline step that waits for its run asks here, on each turn of workflow-service's timer, how the
     * run is: {status, ended, message, endedAt}. Read as the workspace named (row security), and only a run of that
     * workspace: another's, or none, is "No run". Nothing changes.
     */
    @PostMapping(value = "/runState", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runState(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody StateRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getRunId() == null) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId) and the run (runId)."));
        }
        TenantContext.set(request.getTenantId(), WORKSPACE_ROLE, null, "workflow-service");
        try {
            Optional<JobQueue> run = this.runs.findById(request.getRunId())
                .filter(q -> request.getTenantId().equals(q.getTenantId()));
            if (!run.isPresent()) {
                return ResponseEntity.ok(new ResponseDto("ERROR", "No run " + request.getRunId() + " in this workspace."));
            }
            JobQueue q = run.get();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("status", q.getJobStatus() == null ? null : q.getJobStatus().name());
            state.put("ended", RunSlo.isTerminal(q.getJobStatus()));
            state.put("message", q.getJobStatusMessage());
            state.put("endedAt", q.getEndTime() == null ? null : q.getEndTime().toString());
            if (request.getResults() != null && !request.getResults().isNull() && q.getJobStatus() == JobStatus.Completed) {
                state.put("results", this.resultsOf(request.getTenantId(), q, request.getResults()));
            }
            return ResponseEntity.ok(new ResponseDto("SUCCESS", "Run state.", state));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * MIG-424: what the step chose of a completed run's output, masked for a reader who is no one person (the request's
     * history is read by many): {step, rowCount, values | rows, truncated, masked, problem}. A selection that does not stand,
     * or an output that cannot be read, answers its problem in words, never an error: the step keeps the run's outcome.
     */
    private Map<String, Object> resultsOf(long tenantId, JobQueue run, JsonNode asked) {
        List<String> problems = new ArrayList<>();
        RunResults.Selection selection = RunResults.Selection.of(asked, problems);
        if (!problems.isEmpty()) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("problem", String.join("; ", problems) + ".");
            return refused;
        }
        try {
            PipelineDefinition definition = this.steps.pinnedDefinition(run.getJobQueueId()).flatMap(this.definitions::byId)
                .map(PipelineDefinitionStore.Stored::definition).orElse(null);
            return this.results.read(run.getJobQueueId(), selection, this.masks.of(tenantId, definition)).toMap(selection.take);
        } catch (Exception unreadable) {
            this.logger.warn("Run {}'s results could not be read for its workflow step: {}", run.getJobQueueId(), unreadable.getMessage());
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("problem", "The run's output could not be read.");
            return failed;
        }
    }

    /** Which job a workflow designer or a publish asks about. */
    public static class PipelineRequest {
        private Long tenantId;
        private Long jobId;

        public Long getTenantId() { return tenantId; }

        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

        public Long getJobId() { return jobId; }

        public void setJobId(Long jobId) { this.jobId = jobId; }
    }

    /**
     * MIG-424: a job of the named workspace as a workflow publish checks a run_pipeline step against it -- its declared
     * parameters, its steps and their output columns (PipelineOutputFields). Another workspace's job is "No pipeline", as
     * one that does not exist. Nothing changes.
     */
    @PostMapping(value = "/pipeline", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> pipeline(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody PipelineRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getJobId() == null) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId) and the job (jobId)."));
        }
        TenantContext.set(request.getTenantId(), WORKSPACE_ROLE, null, "workflow-service");
        try {
            Optional<Map<String, Object>> found = this.fields.of(request.getTenantId(), request.getJobId());
            return ResponseEntity.ok(found.isPresent() ? new ResponseDto("SUCCESS", "Pipeline.", found.get())
                : new ResponseDto("ERROR", "No pipeline " + request.getJobId() + " in this workspace."));
        } finally {
            TenantContext.clear();
        }
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a workflow run request without the internal token.");
        }
        return ok;
    }
}
