package process.backfill;

import org.barco.platform.security.ReadOnlyList;
import org.barco.platform.security.WorkspaceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MIG-347: the console's backfills, under sourceJob.json -- Schedules' page, so a tenant user needs it (a reviewer or a
 * viewer without Schedules is refused by the page gate), and a tenant user's are their own schedules' (JobOwnership).
 * Running a schedule's past dates is running an existing schedule, so a MANAGED workspace's customers may (owner,
 * 2026-09-29: Run now, Run with).
 *
 * <ul>
 *   <li>POST /sourceJob.json/backfills/preview {jobId, from, to, step, concurrency, rerunCompleted, parameters}: the periods,
 *   which already have a completed run, the values, the cost estimate (administrators), and what start would refuse.</li>
 *   <li>POST /sourceJob.json/backfills/start (the same body): starts it.</li>
 *   <li>GET /sourceJob.json/backfills?jobId&amp;status&amp;page&amp;limit: the workspace's backfills, newest first, with progress.</li>
 *   <li>GET /sourceJob.json/backfills/detail?backfillId&amp;state&amp;page&amp;sort&amp;dir: one, with a page of its periods and their runs, and
 *   (administrators) its estimate and what its runs were billed so far.</li>
 *   <li>POST /sourceJob.json/backfills/pause, /resume, /cancel, /retryFailed {backfillId}.</li>
 *   <li>GET /sourceJob.json/backfills/ofRun?jobQueueId: the backfill a run is part of, for the run's page (null for none).</li>
 * </ul>
 * Refusals are 4xx with their sentence: 400 not valid, 404 not found (or not yours), 409 the backfill's or workspace's state,
 * 422 parameters that do not stand.
 */
// ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): a workspace's backfills, their runs and every start,
// pause, resume, cancel and retry are "Work in this workspace"'s only.
@WorkspaceData
@RestController
@PreAuthorize("hasRole('TENANT_USER')")
public class BackfillRestApi {

    private static final Logger logger = LoggerFactory.getLogger(BackfillRestApi.class);

    private final Backfills backfills;

    public BackfillRestApi(Backfills backfills) {
        this.backfills = backfills;
    }

    @ReadOnlyList("a backfill's periods")
    @PostMapping("/sourceJob.json/backfills/preview")
    public ResponseEntity<ResponseDto> preview(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Backfill preview.", this.backfills.preview(request(body))));
    }

    @PostMapping("/sourceJob.json/backfills/start")
    public ResponseEntity<ResponseDto> start(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> {
            Map<String, Object> started = this.backfills.start(request(body), Backfills.Origin.console(TenantContext.getAppUserId()));
            return new ResponseDto(ProcessUtil.SUCCESS, String.format("Backfill #%s started: %s period(s).", started.get("backfillId"),
                started.get("total")), started);
        });
    }

    @GetMapping("/sourceJob.json/backfills")
    public ResponseEntity<ResponseDto> list(@RequestParam(required = false) Long jobId, @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        return answer(() -> {
            Map<String, Object> found = this.backfills.list(jobId, status, page, limit);
            return new ResponseDto(ProcessUtil.SUCCESS, found.get("total") + " backfill(s).", found);
        });
    }

    @GetMapping("/sourceJob.json/backfills/detail")
    public ResponseEntity<ResponseDto> detail(@RequestParam Long backfillId, @RequestParam(required = false) String state,
        @RequestParam(required = false) Integer page, @RequestParam(required = false) String sort, @RequestParam(required = false) String dir) {
        // Owner 2026-10-27: the periods in a column's order (BackfillStore.SLOT_SORTS); an unknown one is a 400 naming them.
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Backfill read.", this.backfills.detail(backfillId, state, page, false, sort, dir)));
    }

    @PostMapping("/sourceJob.json/backfills/pause")
    public ResponseEntity<ResponseDto> pause(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Backfill paused: nothing more is queued until it is resumed.",
            this.backfills.pause(id(body), TenantContext.getAppUserId(), false)));
    }

    @PostMapping("/sourceJob.json/backfills/resume")
    public ResponseEntity<ResponseDto> resume(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Backfill resumed.", this.backfills.resume(id(body), false)));
    }

    @PostMapping("/sourceJob.json/backfills/cancel")
    public ResponseEntity<ResponseDto> cancel(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "Backfill cancelled: nothing more is queued; runs already started finish.",
            this.backfills.cancel(id(body), TenantContext.getAppUserId(), false)));
    }

    @PostMapping("/sourceJob.json/backfills/retryFailed")
    public ResponseEntity<ResponseDto> retryFailed(@RequestBody(required = false) Map<String, Object> body) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "The failed periods are queued again.",
            this.backfills.retryFailed(id(body), false)));
    }

    @GetMapping("/sourceJob.json/backfills/ofRun")
    public ResponseEntity<ResponseDto> ofRun(@RequestParam Long jobQueueId) {
        return answer(() -> new ResponseDto(ProcessUtil.SUCCESS, "The run's backfill.", this.backfills.ofRun(jobQueueId).orElse(null)));
    }

    static Backfills.Request request(Map<String, Object> body) {
        Backfills.Request r = new Backfills.Request();
        if (body == null) {
            return r;
        }
        r.jobId = number(body.get("jobId"));
        r.from = text(body.get("from"));
        r.to = text(body.get("to"));
        r.step = text(body.get("step"));
        Long concurrency = number(body.get("concurrency"));
        r.concurrency = concurrency == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, concurrency));
        Object rerun = body.get("rerunCompleted");
        r.rerunCompleted = rerun instanceof Boolean ? (Boolean) rerun : rerun != null && "true".equalsIgnoreCase(String.valueOf(rerun));
        Object parameters = body.get("parameters");
        if (parameters instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> given = (Map<String, Object>) parameters;
            r.parameters = given;
        } else if (parameters != null) {
            throw Backfills.invalid("parameters is an object of values by name.");
        }
        return r;
    }

    static Long id(Map<String, Object> body) {
        return body == null ? null : number(body.get("backfillId"));
    }

    static Long number(Object value) {
        if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException wrong) {
            throw Backfills.invalid("Expected a number, got " + value + ".");
        }
    }

    static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static List<String> texts(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection) {
            ((Collection<?>) value).forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }

    private static ResponseEntity<ResponseDto> answer(Supplier<ResponseDto> work) {
        try {
            return new ResponseEntity<>(work.get(), HttpStatus.OK);
        } catch (Backfills.Refused refused) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, refused.getMessage()), refused.getStatus());
        } catch (RuntimeException failed) {
            logger.error("A backfill request failed.", failed);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
