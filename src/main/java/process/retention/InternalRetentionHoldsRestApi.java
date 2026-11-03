package process.retention;

import org.barco.platform.tenancy.RowSecurity;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tools redesign (owner-approved 2026-11-02): media-service removes Audio Transcript's recordings and the Document
 * Converter's unkept conversions after 30 days by itself -- with no retention policy at all -- and asks here first whether a
 * legal hold keeps the workspace's data: POST /internal/retention/holds {tenantId} -> {workspace: true|false}. Those classes
 * know no run, so only a workspace hold reaches them. Service token only; the gateway never exposes /internal.
 *
 * <p>Reports redesign, feature 16 (2026-11-03): a report with review comments is not deleted while a hold keeps it -- media
 * names the run that made it ({tenantId, runId}) and is also answered {run: true|false}: held by a run hold on that run, or
 * a pipeline hold on its pipeline. A run that is not the workspace's is never said held by another workspace's hold.
 */
@RestController
@RequestMapping("/internal/retention")
public class InternalRetentionHoldsRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalRetentionHoldsRestApi.class);
    private final RetentionStore store;
    private final byte[] token;

    public InternalRetentionHoldsRestApi(RetentionStore store, @Value("${internal.service-token:}") String token) {
        this.store = store;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Whose holds are asked about. */
    public static class HoldsRequest {
        private Long tenantId;
        private Long runId;

        public Long getRunId() {
            return this.runId;
        }

        public void setRunId(Long runId) {
            this.runId = runId;
        }

        public Long getTenantId() {
            return this.tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }
    }

    @PostMapping(value = "/holds", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> holds(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) HoldsRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getTenantId() < 1) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId)."));
        }
        long tenantId = request.getTenantId();
        // No person is signed in: the read is made as the workspace itself (legal_hold has row security; with no caller it
        // would answer "no holds" -- the one wrong answer here).
        Holds holds = RowSecurity.forTenant(tenantId, () -> this.store.activeHolds(tenantId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspace", holds.workspace);
        Long runId = request.getRunId();
        if (runId != null) {
            boolean run = runId > 0 && holds.runIds.contains(runId);
            if (!run && runId > 0 && !holds.jobIds.isEmpty()) {
                Long pipeline = RowSecurity.forTenant(tenantId, () -> this.store.runPipeline(tenantId, runId));
                run = pipeline != null && holds.jobIds.contains(pipeline);
            }
            out.put("run", run);
        }
        return ResponseEntity.ok(new ResponseDto("SUCCESS", "Holds.", out));
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a retention holds request without the internal token.");
        }
        return ok;
    }
}
