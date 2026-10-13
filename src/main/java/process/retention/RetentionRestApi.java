package process.retention;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MIG-354: data retention, under the prefix the gateway already sends to Core (/setting.json). A workspace's
 * administrators work on their own workspace; a platform administrator names one (tenantId). Nobody else: a reviewer or
 * viewer is refused (403), as for every other administration setting. Every refusal is a 4xx with its sentence.
 *
 * <ul>
 *   <li>GET setting.json/retention -- the policy, each class with its last result, the holds, the last pass;</li>
 *   <li>POST setting.json/retention {enabled, classes: {CLASS: days | null}} -- saves; nothing is removed until a pass;</li>
 *   <li>POST setting.json/retention/preview {classes?} -- what a pass would remove now, per class (writes nothing);</li>
 *   <li>POST setting.json/retention/confirm {dataClass, expectedCount} -- allows a pass over the platform's share;</li>
 *   <li>POST setting.json/retention/run -- "Run now": a pass in the background (its id);</li>
 *   <li>POST setting.json/retention/holds {scope, jobId | runId, reason}, POST .../holds/release {id, reason};</li>
 *   <li>GET setting.json/retention/passes?limit=, GET setting.json/retention/audit?limit= -- the history;</li>
 *   <li>GET and POST setting.json/retention/platform -- the audit floor and the share (platform administrators only).</li>
 * </ul>
 */
@RestController
@RequestMapping("/setting.json/retention")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class RetentionRestApi {

    private final RetentionService retention;

    public RetentionRestApi(RetentionService retention) {
        this.retention = retention;
    }

    /** The workspace a caller works on: their own, or the one a platform administrator names. */
    static long workspace(Object named) {
        if (TenantContext.isPlatformAdmin()) {
            Long tenant = RetentionService.asLong(named);
            if (tenant == null || tenant <= 0) {
                throw new RetentionService.Refused(HttpStatus.BAD_REQUEST, "Name the workspace (tenantId).");
            }
            return tenant;
        }
        Long tenant = TenantContext.getTenantId();
        if (tenant == null || tenant <= 0) {
            throw new RetentionService.Refused(HttpStatus.BAD_REQUEST, "Retention belongs to a workspace; this account is not attached to one.");
        }
        return tenant;
    }

    private static Object named(Map<String, Object> body) {
        return body == null ? null : body.get("tenantId");
    }

    @GetMapping
    public ResponseEntity<ResponseDto> view(@RequestParam(value = "tenantId", required = false) Long tenantId) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Retention policy.", this.retention.view(workspace(tenantId))));
    }

    @PostMapping
    public ResponseEntity<ResponseDto> save(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> {
            Map<String, Object> saved = this.retention.save(workspace(named(body)), body, TenantContext.getAppUserId());
            Object changes = saved.get("changes");
            boolean none = changes instanceof List && ((List<?>) changes).isEmpty();
            return new ResponseDto(ProcessUtil.SUCCESS, none ? "Nothing changed." : "Retention policy saved.", saved);
        });
    }

    @PostMapping("/preview")
    public ResponseEntity<ResponseDto> preview(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> {
            Map<String, Object> preview = this.retention.preview(workspace(named(body)), body);
            return new ResponseDto(ProcessUtil.SUCCESS, preview.get("items") + " item(s) would be removed now.", preview);
        });
    }

    @PostMapping("/confirm")
    public ResponseEntity<ResponseDto> confirm(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> {
            Map<String, Object> confirmed = this.retention.confirm(workspace(named(body)), body, TenantContext.getAppUserId());
            return new ResponseDto(ProcessUtil.SUCCESS, String.valueOf(confirmed.get("message")), confirmed);
        });
    }

    @PostMapping("/run")
    public ResponseEntity<ResponseDto> run(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Retention pass started.",
            this.retention.runNow(workspace(named(body)), TenantContext.getAppUserId())));
    }

    @PostMapping("/holds")
    public ResponseEntity<ResponseDto> placeHold(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Legal hold placed.",
            this.retention.placeHold(workspace(named(body)), body, TenantContext.getAppUserId())));
    }

    @PostMapping("/holds/release")
    public ResponseEntity<ResponseDto> releaseHold(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Legal hold released.",
            this.retention.releaseHold(workspace(named(body)), body, TenantContext.getAppUserId())));
    }

    @GetMapping("/passes")
    public ResponseEntity<ResponseDto> passes(@RequestParam(value = "tenantId", required = false) Long tenantId,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return answer(() -> {
            List<Map<String, Object>> passes = this.retention.passes(workspace(tenantId), limit == null ? 20 : limit);
            return new ResponseDto(ProcessUtil.SUCCESS, passes.size() + " retention pass(es).", passes);
        });
    }

    @GetMapping("/audit")
    public ResponseEntity<ResponseDto> audit(@RequestParam(value = "tenantId", required = false) Long tenantId,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return answer(() -> {
            List<Map<String, Object>> lines = this.retention.audit(workspace(tenantId), limit == null ? 100 : limit);
            return new ResponseDto(ProcessUtil.SUCCESS, lines.size() + " audit line(s).", lines);
        });
    }

    @GetMapping("/platform")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ResponseDto> platform() {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Retention platform settings.", this.retention.platform()));
    }

    @PostMapping("/platform")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ResponseDto> savePlatform(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Retention platform settings saved.",
            this.retention.savePlatform(body, TenantContext.getAppUserId())));
    }

    private static ResponseEntity<ResponseDto> answer(Supplier<ResponseDto> work) {
        try {
            return new ResponseEntity<>(work.get(), HttpStatus.OK);
        } catch (RetentionService.Refused refused) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, refused.getMessage()), refused.getStatus());
        }
    }
}
