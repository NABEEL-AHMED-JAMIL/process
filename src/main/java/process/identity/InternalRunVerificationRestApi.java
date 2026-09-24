package process.identity;

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
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.security.RunCallbackTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
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
 */
@RestController
@RequestMapping("/internal")
public class InternalRunVerificationRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalRunVerificationRestApi.class);
    private final RunCallbackTokens tokens;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final PipelineRepository pipelines;
    private final byte[] token;

    public InternalRunVerificationRestApi(RunCallbackTokens tokens, JobQueueRepository runs, SourceJobRepository jobs,
        PipelineRepository pipelines, @Value("${internal.service-token:}") String token) {
        this.tokens = tokens;
        this.runs = runs;
        this.jobs = jobs;
        this.pipelines = pipelines;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {jobId, token, variant: "callback" | "report"}. */
    @PostMapping(value = "/runs/{jobQueueId}/verify-callback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> verifyCallback(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @PathVariable("jobQueueId") Long jobQueueId, @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
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
        verdict.put("workerSteps", tenantId != null && pipelineId != null ? this.workerSteps(pipelineId, tenantId)
            : Collections.emptyList());
        return new ResponseEntity<>(verdict, HttpStatus.OK);
    }

    /** Body {promptId}. */
    @PostMapping(value = "/pipelines/countUsingPrompt", produces = MediaType.APPLICATION_JSON_VALUE)
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
        answer.put("count", this.pipelines.countUsingPrompt(id));
        return new ResponseEntity<>(answer, HttpStatus.OK);
    }

    /** The steps this run's pipeline hands to a worker: [{stepTag, promptId}], in position order. */
    private List<Map<String, Object>> workerSteps(String pipelineId, Long tenantId) {
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(pipelineId.trim(), tenantId, Status.Delete);
        List<Map<String, Object>> steps = new ArrayList<>();
        if (found.isEmpty()) {
            return steps;
        }
        for (PipelineField field : AiStepService.stepsOf(found.get(0))) {
            if ("worker".equals(field.getRunIn())) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("stepTag", field.getTagKey());
                step.put("promptId", field.getPromptId());
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
