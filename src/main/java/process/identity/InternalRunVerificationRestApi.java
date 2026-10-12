package process.identity;

import org.springframework.beans.factory.annotation.Autowired;
import java.util.function.Supplier;
import process.security.RunWorkspace;
import org.barco.platform.tenancy.AcrossTenants;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.ai.AiStepService;
import process.ai.ModelChoiceStore;
import process.ai.ModelProfiles;
import process.ai.RunAiStep;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineUsage;
import process.security.RunCallbackTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the AI service asks Core about a pipeline run (MIG-189, MIG-188, ADR-020).
 *
 * /internal/runs/{jobQueueId}/verify-callback: a worker's run token is Core's to check, so AI asks
 * here instead of reading job_queue. A valid token is answered with everything AI needs to run the
 * step without any Core table -- the job, its tenant, its pipeline, whether the run is over, and the
 * steps its pipeline hands to a worker -- and that answer is the ONLY place AI takes a tenant id from
 * another service. A refusal is {valid:false} and nothing else: the reason is logged here, never
 * disclosed. The "callback" variant refuses a run that is over; the "report" variant accepts one,
 * because a usage report may arrive after the run closed and must not be lost from the bill.
 *
 * /internal/pipelines/countUsingPrompt: how many live pipelines name a prompt, which AI asks before
 * every prompt delete and refuses the delete if it cannot find out.
 *
 * /internal/pipelines/apiRequestUsers: which of a workspace's step-engine pipelines run its API requests, which
 * integration-service asks for an API collection's "Used by" and before a request's delete.
 */
@RestController
@RequestMapping("/internal")
public class InternalRunVerificationRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalRunVerificationRestApi.class);
    private final RunCallbackTokens tokens;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final PipelineRepository pipelines;
    private final ModelChoiceStore modelChoices;
    private final byte[] token;
    /** Which pipelines use a prompt or an API request, step-engine ones included; absent in a test that does not ask. */
    private PipelineUsage usage;

    /** A collection's requests in one question; integration-service asks per collection, folder or request. */
    static final int MAX_REQUEST_IDS = 2000;

    public InternalRunVerificationRestApi(RunCallbackTokens tokens, JobQueueRepository runs, SourceJobRepository jobs,
        PipelineRepository pipelines, ModelChoiceStore modelChoices, @Value("${internal.service-token:}") String token) {
        this.tokens = tokens;
        this.runs = runs;
        this.jobs = jobs;
        this.pipelines = pipelines;
        this.modelChoices = modelChoices;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Autowired(required = false)
    public void setUsage(PipelineUsage usage) {
        this.usage = usage;
    }

    /**
     * Set by Spring (RunWorkspace is a component). Without it -- a test that builds this by hand, or a slice context
     * without the component -- the work runs unscoped: on the application's own pool that is no workspace at all, so the
     * callback is refused, never widened.
     */
    private RunWorkspace runWorkspace;

    @Autowired(required = false)
    public void setRunWorkspace(RunWorkspace runWorkspace) {
        this.runWorkspace = runWorkspace;
    }

    /** The work as the run's workspace, and only it (MIG-258): a worker's callback names nothing else. */
    private <T> T asTheRunsWorkspace(Long jobQueueId, Supplier<T> work) {
        return this.runWorkspace == null ? work.get() : RowSecurity.forTenant(this.runWorkspace.of(jobQueueId), work);
    }

    /** Body {jobId, token, variant: "callback" | "report"}. */
    @PostMapping(value = "/runs/{jobQueueId}/verify-callback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> verifyCallback(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @PathVariable("jobQueueId") Long jobQueueId, @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        return this.asTheRunsWorkspace(jobQueueId, () -> {
            Object variant = body == null ? null : body.get("variant");
            boolean report = "report".equals(variant);
            if (!report && !"callback".equals(variant)) {
                return new ResponseEntity<>(Collections.singletonMap("message", "variant must be callback or report."), HttpStatus.BAD_REQUEST);
            }
            Long jobId = body.get("jobId") instanceof Number ? ((Number) body.get("jobId")).longValue() : null;
            String workerToken = body.get("token") == null ? null : body.get("token").toString();
            Optional<RunCallbackTokens.Refusal> refused = report
                ? this.tokens.verifyForReport(jobId, jobQueueId, workerToken)
                : this.tokens.verify(jobId, jobQueueId, workerToken);
            if (refused.isPresent()) {
                this.logger.warn("Refused a {} token for job {} run {}: {}.", variant, jobId, jobQueueId, refused.get());
                return new ResponseEntity<>(Collections.singletonMap("valid", false), HttpStatus.OK);
            }
            JobQueue run = this.runs.findById(jobQueueId).orElse(null);
            // Owner rule "keep the bill" (2026-09-24): the run row and its token are the proof, not the job's current status.
            // A run whose job was deleted (or switched off) after it started still names its workspace -- the one stamped on
            // the run (V102) -- and its job is found whatever its status, so its usage report is billed and its callback
            // lands. The job's own tenant only for a run row older than that stamp.
            Optional<SourceJob> job = run == null ? Optional.empty() : this.jobs.findByJobIdAndJobStatus(run.getJobId(), Status.Active);
            if (run != null && !job.isPresent()) {
                job = this.jobs.findById(run.getJobId());
            }
            Long tenantId = run != null && run.getTenantId() != null ? run.getTenantId() : job.map(SourceJob::getTenantId).orElse(null);
            String pipelineId = job.map(SourceJob::getTaskDetail).map(task -> task.getPipelineId()).orElse(null);
            Map<String, Object> verdict = new LinkedHashMap<>();
            verdict.put("valid", true);
            verdict.put("jobId", run == null ? jobId : run.getJobId());
            verdict.put("jobQueueId", jobQueueId);
            verdict.put("tenantId", tenantId);
            verdict.put("pipelineId", pipelineId);
            verdict.put("terminal", run != null && RunCallbackTokens.isOver(run.getJobStatus()));
            verdict.put("workerSteps", tenantId != null && pipelineId != null
                ? this.workerSteps(pipelineId, tenantId, run, job.orElse(null)) : Collections.emptyList());
            return new ResponseEntity<>(verdict, HttpStatus.OK);
        });
    }

    /** Body {promptId}. */
    @PostMapping(value = "/pipelines/countUsingPrompt", produces = MediaType.APPLICATION_JSON_VALUE)
    @AcrossTenants("AI asks whether any workspace's pipeline still uses a prompt before it deletes it (service token)")
    public ResponseEntity<?> countUsingPrompt(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Object promptId = body == null ? null : body.get("promptId");
        if (!(promptId instanceof Number)) {
            return new ResponseEntity<>(Collections.singletonMap("message", "promptId is required."), HttpStatus.BAD_REQUEST);
        }
        long id = ((Number) promptId).longValue();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("promptId", id);
        // Since the console review of 2026-10-07 a step-engine pipeline whose AI step names the prompt counts too.
        answer.put("count", this.usage == null ? this.pipelines.countUsingPrompt(id) : this.usage.countUsingPrompt(id));
        return new ResponseEntity<>(answer, HttpStatus.OK);
    }

    /**
     * Body {tenantId, requestIds: [..]}: the workspace's live step-engine pipelines whose steps run one of these API
     * requests (Read API, Enrich -- any task whose config names an api-request), one row per pipeline and request:
     * {pipelineKey, pipelineId, pipelineName, definitionVersion, requestId, version (the collection version the steps pin;
     * null for the request as it is now), unpinned, steps}. integration-service shows them as an API's "Used by" and
     * refuses to delete a request while any pipeline runs it. Runs as that workspace (row-level security).
     */
    @PostMapping(value = "/pipelines/apiRequestUsers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> apiRequestUsers(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Object tenant = body == null ? null : body.get("tenantId");
        Object requested = body == null ? null : body.get("requestIds");
        if (!(tenant instanceof Number) || ((Number) tenant).longValue() <= 0 || !(requested instanceof List)) {
            return new ResponseEntity<>(Collections.singletonMap("message", "tenantId and requestIds are required."), HttpStatus.BAD_REQUEST);
        }
        List<Long> requestIds = new ArrayList<>();
        for (Object id : (List<?>) requested) {
            if (id instanceof Number) {
                requestIds.add(((Number) id).longValue());
            }
        }
        if (requestIds.size() > MAX_REQUEST_IDS) {
            return new ResponseEntity<>(Collections.singletonMap("message", "At most " + MAX_REQUEST_IDS + " requestIds."), HttpStatus.BAD_REQUEST);
        }
        long tenantId = ((Number) tenant).longValue();
        List<Map<String, Object>> users = this.usage == null ? Collections.emptyList()
            : RowSecurity.forTenant(tenantId, () -> this.usage.apiRequestUsers(tenantId, requestIds));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("tenantId", tenantId);
        answer.put("users", users);
        return new ResponseEntity<>(answer, HttpStatus.OK);
    }

    /**
     * The steps this run's pipeline hands to a worker, in position order: [{stepTag, promptId}], and since MIG-242 the
     * model each asks for (modelProfile, an ai-service option id; absent for the step's default) and the pipeline's
     * source task (sourceTaskId), which ai-service runs the worker's call on -- the worker never names a model itself.
     * The model is the one the run was prepared with (run_ai_step, written by the pre-dispatch phase), so changing the
     * schedule mid-run does not move a running run; a run prepared before V182 falls back to the run's and the job's
     * settings as they are now.
     */
    private List<Map<String, Object>> workerSteps(String pipelineId, Long tenantId, JobQueue run, SourceJob job) {
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(pipelineId.trim(), tenantId, Status.Delete);
        List<Map<String, Object>> steps = new ArrayList<>();
        if (found.isEmpty()) {
            return steps;
        }
        Map<String, RunAiStep> prepared = new HashMap<>();
        if (run != null) {
            for (RunAiStep recorded : this.modelChoices.stepsOfRun(run.getJobQueueId())) {
                if (recorded.attempt != null && recorded.attempt == run.getAttempt()) {
                    prepared.put(recorded.stepKey, recorded);
                }
            }
        }
        ModelProfiles now = ModelProfiles.of(job == null ? null : job.getModelProfiles(), run == null ? null : run.getModelProfiles());
        Long sourceTaskId = job == null || job.getTaskDetail() == null ? null : job.getTaskDetail().getTaskDetailId();
        for (PipelineField field : AiStepService.stepsOf(found.get(0))) {
            if ("worker".equals(field.getRunIn())) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("stepTag", field.getTagKey());
                step.put("promptId", field.getPromptId());
                String profile = prepared.containsKey(field.getTagKey()) ? prepared.get(field.getTagKey()).modelProfile
                    : now.forStep(field.getTagKey()).profile;
                if (profile != null) {
                    step.put("modelProfile", profile);
                }
                if (sourceTaskId != null) {
                    step.put("sourceTaskId", sourceTaskId);
                }
                steps.add(step);
            }
        }
        return steps;
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a run verification without the internal token.");
        }
        return ok;
    }
}
