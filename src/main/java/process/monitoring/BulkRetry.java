package process.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.pipeline.StepTimelineService;
import process.security.TenantContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import static process.util.ProcessUtil.SUCCESS;

/**
 * MIG-355: POST sourceJob.json/bulkRetry -- a workspace administrator retries many failed runs at once, each run on its own
 * through the single retry's path (StepTimelineService.retry: MIG-345's rules, its own transaction), so one run's refusal
 * never takes another back. The bulk review's pattern (RunReviewBulk):
 *
 * <ul>
 *   <li>At most {@value #MAX_ITEMS} distinct runs (duplicates collapsed); none, or more, is a 400 and nothing is done.</li>
 *   <li>{@code from}: "failed_step" (the default) resumes each from the step that failed; "start" runs each again from the
 *   start (an administrator's, as for one run).</li>
 *   <li>{@code bulkId}: the client's (^[A-Za-z0-9-]{8,64}$), else one is made; every run's audit line ends
 *   " Bulk retry &lt;bulkId&gt;." A run already retried is refused by the retry itself ("the run moved on"), so the same
 *   request sent twice retries each run once.</li>
 *   <li>{@code dryRun}: each run's retry plan only, nothing written -- the console's confirm panel asks it first.</li>
 *   <li>At most {@value #RATE_CAP} valid bulk retries a rolling minute per caller (dry runs count): a 429. Held in this
 *   instance's memory (RunReviewBulk's note on more than one Core instance applies).</li>
 * </ul>
 * The answer is per run: done (queued again), skipped with the retry's own reason (run-not-failed, run-in-flight,
 * pipeline-changed, input-changed, outputs-expired, not-resumable, not-found, ...; part-of-backfill for a backfill's run, which its
 * backfill retries), or failed.
 */
@Service
public class BulkRetry {

    public static final int MAX_ITEMS = 50;
    public static final int RATE_CAP = 10;
    static final long RATE_WINDOW_MILLIS = 60_000L;
    static final Pattern BULK_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    static final String TOO_MANY = "Too many bulk retries in a minute; wait a moment and try again.";
    /** A skipped run's reason when it is one of a backfill's: the backfill's own Retry failed retries it, with its date. */
    static final String PART_OF_BACKFILL = "part-of-backfill";

    static String backfillSentence(Object backfillId) {
        return String.format("Part of backfill #%s: retry failed days from the backfill.", backfillId);
    }

    private static final Logger logger = LoggerFactory.getLogger(BulkRetry.class);

    private final StepTimelineService runs;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public BulkRetry(StepTimelineService runs) {
        this(runs, System::currentTimeMillis);
    }

    BulkRetry(StepTimelineService runs, LongSupplier clock) {
        this.runs = runs;
        this.clock = clock;
    }

    /** Retries (or, dry, plans) every run of the request as the caller; refused as a whole when it is not valid. */
    @SuppressWarnings("unchecked")
    public ResponseDto retry(Map<String, Object> body) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "Runs belong to a workspace; this account is not attached to one.");
        }
        if (body == null) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "Send jobQueueIds.");
        }
        Set<Long> ids = new LinkedHashSet<>();
        Object listed = body.get("jobQueueIds");
        if (listed instanceof List) {
            for (Object id : (List<Object>) listed) {
                if (id instanceof Number) {
                    ids.add(((Number) id).longValue());
                } else if (id != null) {
                    try {
                        ids.add(Long.valueOf(String.valueOf(id).trim()));
                    } catch (NumberFormatException unreadable) {
                        throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "jobQueueIds are run ids.");
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "List the runs to retry (jobQueueIds).");
        }
        if (ids.size() > MAX_ITEMS) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, String.format("At most %d runs at once; %d were listed.", MAX_ITEMS, ids.size()));
        }
        String from = body.get("from") == null ? "failed_step" : String.valueOf(body.get("from")).trim();
        if (!"failed_step".equals(from) && !"start".equals(from)) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "from is failed_step or start.");
        }
        Object given = body.get("bulkId");
        String bulkId = given == null || String.valueOf(given).trim().isEmpty() ? UUID.randomUUID().toString() : String.valueOf(given).trim();
        if (!BULK_ID.matcher(bulkId).matches()) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "bulkId is 8 to 64 letters, digits and '-'.");
        }
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        this.countAgainstTheCap();

        List<Map<String, Object>> done = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                ResponseDto answer = dryRun ? this.runs.retryPlan(id) : this.runs.retry(id, from, bulkId);
                Map<String, Object> view = answer.getData() instanceof Map ? (Map<String, Object>) answer.getData() : new LinkedHashMap<>();
                Map<String, Object> item = item(id, view);
                boolean ok = dryRun ? ("start".equals(from) ? Boolean.TRUE.equals(view.get("restartable")) : Boolean.TRUE.equals(view.get("resumable")))
                    : SUCCESS.equals(answer.getStatus());
                if (ok) {
                    item.put("message", dryRun ? ("start".equals(from) ? "Would run again from the start."
                        : "Would resume from step " + view.get("fromKey") + ".") : answer.getMessage());
                    done.add(item);
                } else if (view.get("backfillId") != null) {
                    // UX review 2026-10-13: a backfill's run is retried by its backfill (Runs › Backfills, Retry failed).
                    item.put("reason", PART_OF_BACKFILL);
                    item.put("message", backfillSentence(view.get("backfillId")));
                    item.put("backfillId", view.get("backfillId"));
                    skipped.add(item);
                } else {
                    Object kind = dryRun && "start".equals(from) ? null : view.get("refusalKind");
                    item.put("reason", kind == null ? (view.isEmpty() ? "not-found" : "not-resumable") : kind);
                    Object why = dryRun && "start".equals(from) ? view.get("restartRefusal") : (view.get("refusal") != null ? view.get("refusal")
                        : answer.getMessage());
                    item.put("message", why == null ? answer.getMessage() : why);
                    skipped.add(item);
                }
            } catch (RuntimeException failure) {
                logger.warn("Bulk retry {} by user {}: run {} could not be retried; the other runs go on.", bulkId, TenantContext.getAppUserId(),
                    id, failure);
                Map<String, Object> item = item(id, new LinkedHashMap<>());
                item.put("message", "Could not be retried: an internal error (logged).");
                failed.add(item);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bulkId", bulkId);
        data.put("action", "retry");
        data.put("from", from);
        data.put("dryRun", dryRun);
        data.put("requested", ids.size());
        data.put("done", done);
        data.put("skipped", skipped);
        data.put("failed", failed);
        String message = String.format("%d %s, %d skipped", done.size(), dryRun ? "would be retried" : "retried", skipped.size())
            + (failed.isEmpty() ? "" : String.format(", %d failed", failed.size())) + ".";
        if (!dryRun) {
            logger.info("Bulk retry {} of workspace {} by user {}: {} runs from {} -- {}", bulkId, tenantId, TenantContext.getAppUserId(), ids.size(),
                from, message);
        }
        return new ResponseDto(SUCCESS, message, data);
    }

    private static Map<String, Object> item(long id, Map<String, Object> view) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        Object job = view.get("jobId");
        item.put("label", "Run " + id);
        item.put("group", job == null ? null : "Schedule " + job);
        item.put("link", job == null ? null : "/pipelines/schedules/" + job + "/runs/" + id);
        return item;
    }

    private void countAgainstTheCap() {
        String caller = TenantContext.getAppUserId() != null ? "user:" + TenantContext.getAppUserId() : "name:" + TenantContext.getUsername();
        long now = this.clock.getAsLong();
        Deque<Long> times = this.recent.computeIfAbsent(caller, key -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - RATE_WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= RATE_CAP) {
                throw new AlertRules.Refused(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
            }
            times.addLast(now);
        }
        if (this.recent.size() > 10_000) {
            this.recent.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || entry.getValue().peekLast() <= now - RATE_WINDOW_MILLIS;
                }
            });
        }
    }
}
