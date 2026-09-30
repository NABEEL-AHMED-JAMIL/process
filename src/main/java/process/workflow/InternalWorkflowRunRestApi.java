package process.workflow;

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
import process.model.dto.ResponseDto;
import process.model.service.SourceJobService;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
    private final byte[] token;

    public InternalWorkflowRunRestApi(SourceJobService jobs, @Value("${internal.service-token:}") String token) {
        this.jobs = jobs;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** What the step asks to run. */
    public static class RunRequest {
        private Long tenantId;
        private Long jobId;
        private Long instanceId;
        private String stepKey;

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
            ResponseDto answer = this.jobs.runSourceJobFor(request.getJobId(), reason);
            this.logger.info("Workflow instance {} step {} asked to run job {} of workspace {}: {}", request.getInstanceId(),
                request.getStepKey(), request.getJobId(), request.getTenantId(), answer.getMessage());
            return ResponseEntity.ok(answer);
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
