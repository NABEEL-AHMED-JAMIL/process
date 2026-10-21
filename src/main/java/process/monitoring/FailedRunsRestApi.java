package process.monitoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.barco.platform.security.WorkspaceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

/**
 * MIG-355: Runs › Failed, under prefixes the gateway already sends to Core and the page access Core already enforces:
 *
 * <ul>
 *   <li>POST message.json/failedRuns {from, to, tenantIds, taskDetailIds, jobIds, tasks, kinds, days, signature, page} --
 *       the window's failed and timed-out runs with their facets and error patterns (FailedRuns). A tenant user needs the
 *       Queue page (message.json is its group); a platform administrator sees every workspace's, with a workspace facet.</li>
 *   <li>POST sourceJob.json/bulkRetry {jobQueueIds, from, bulkId, dryRun} -- a workspace administrator retries many failed
 *       runs, each by the single retry's rules (BulkRetry): at most 50, 10 a minute, per-run results, an audit line each.</li>
 * </ul>
 * Refusals are 4xx with their sentence: 400 for what is not valid, 403 for who may not, 429 for too many.
 */
// ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): a workspace's failed runs, their errors and the bulk
// retry of them are "Work in this workspace"'s only.
@WorkspaceData
@RestController
public class FailedRunsRestApi {

    private static final Logger logger = LoggerFactory.getLogger(FailedRunsRestApi.class);

    private final FailedRuns failedRuns;
    private final BulkRetry bulkRetry;

    public FailedRunsRestApi(FailedRuns failedRuns, BulkRetry bulkRetry) {
        this.failedRuns = failedRuns;
        this.bulkRetry = bulkRetry;
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @PostMapping("/message.json/failedRuns")
    public ResponseEntity<ResponseDto> failedRuns(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> {
            boolean platform = TenantContext.isPlatformAdmin();
            if (!platform && (TenantContext.getTenantId() == null || TenantContext.getTenantId() <= 0)) {
                throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "Runs belong to a workspace; this account is not attached to one.");
            }
            FailedRuns.Query q = new FailedRuns.Query();
            if (body != null) {
                q.from = text(body.get("from"));
                q.to = text(body.get("to"));
                q.tenantIds = platform ? longs(body.get("tenantIds")) : new ArrayList<>();
                q.taskDetailIds = longs(body.get("taskDetailIds"));
                q.jobIds = longs(body.get("jobIds"));
                q.tasks = strings(body.get("tasks"));
                q.kinds = strings(body.get("kinds"));
                q.days = strings(body.get("days"));
                q.signature = text(body.get("signature"));
                Object page = body.get("page");
                q.page = page instanceof Number ? ((Number) page).intValue() : null;
            }
            Map<String, Object> found = this.failedRuns.search(q, platform);
            return new ResponseDto(ProcessUtil.SUCCESS, found.get("total") + " failed run(s).", found);
        });
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @PostMapping("/sourceJob.json/bulkRetry")
    public ResponseEntity<ResponseDto> bulkRetry(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> this.bulkRetry.retry(body));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<Long> longs(Object value) {
        return list(value, v -> v instanceof Number ? ((Number) v).longValue() : Long.valueOf(String.valueOf(v).trim()));
    }

    private static List<String> strings(Object value) {
        return list(value, String::valueOf);
    }

    private static <T> List<T> list(Object value, Function<Object, T> as) {
        List<T> out = new ArrayList<>();
        if (!(value instanceof Collection)) {
            return out;
        }
        for (Object one : (Collection<?>) value) {
            if (one == null) {
                continue;
            }
            try {
                out.add(as.apply(one));
            } catch (NumberFormatException unreadable) {
                throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "Expected ids, got " + one + ".");
            }
        }
        return FailedRuns.cap(out);
    }

    private static ResponseEntity<ResponseDto> answer(Supplier<ResponseDto> work) {
        try {
            return new ResponseEntity<>(work.get(), HttpStatus.OK);
        } catch (AlertRules.Refused refused) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, refused.getMessage()), refused.getStatus());
        } catch (RuntimeException failed) {
            logger.error("A failed-runs request failed.", failed);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
