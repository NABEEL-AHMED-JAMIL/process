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
import process.pipeline.review.BulkEligibility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bulk review (2026-10-12): workflow-service asks, before its bulkAct decides Task inbox tasks about pipeline runs, whether
 * Core's review policy lets each subject be decided in bulk -- POST /api/v1/internal/review/bulkEligibility {tenantId,
 * action: approve|reject, subjects: ["pipeline-run:8301", "pipeline-approval:55"]} -- answered per subject, as that
 * workspace ({@link BulkEligibility}). Service to service, as /internal/workflows/run: the shared X-Internal-Token, checked
 * first; the gateway never exposes /internal.
 */
@RestController
@RequestMapping("/internal/review")
public class InternalReviewEligibilityRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalReviewEligibilityRestApi.class);
    private final BulkEligibility eligibility;
    private final byte[] token;

    public InternalReviewEligibilityRestApi(BulkEligibility eligibility, @Value("${internal.service-token:}") String token) {
        this.eligibility = eligibility;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** What workflow-service asks. */
    public static class EligibilityRequest {
        private Long tenantId;
        private String action;
        private List<String> subjects;

        public Long getTenantId() { return tenantId; }

        public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

        public String getAction() { return action; }

        public void setAction(String action) { this.action = action; }

        public List<String> getSubjects() { return subjects; }

        public void setSubjects(List<String> subjects) { this.subjects = subjects; }
    }

    /** Each subject's answer, keyed by the subject. */
    @PostMapping(value = "/bulkEligibility", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bulkEligibility(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) EligibilityRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        String action = request == null || request.getAction() == null ? "" : request.getAction().trim().toLowerCase(Locale.ROOT);
        if (request == null || request.getTenantId() == null || request.getTenantId() <= 0 || request.getSubjects() == null
            || !("approve".equals(action) || "reject".equals(action))) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId), the action (approve or "
                + "reject) and the subjects."));
        }
        if (request.getSubjects().size() > BulkEligibility.MAX_SUBJECTS) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", String.format("At most %d subjects at once.",
                BulkEligibility.MAX_SUBJECTS)));
        }
        Map<String, Map<String, Object>> answers = this.eligibility.check(request.getTenantId(), "approve".equals(action),
            request.getSubjects());
        long allowed = answers.values().stream().filter(a -> Boolean.TRUE.equals(a.get("allowed"))).count();
        return ResponseEntity.ok(new ResponseDto("SUCCESS", String.format("%d of %d may be decided in bulk.", allowed, answers.size()),
            answers));
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a bulk eligibility request without the internal token.");
        }
        return ok;
    }
}
