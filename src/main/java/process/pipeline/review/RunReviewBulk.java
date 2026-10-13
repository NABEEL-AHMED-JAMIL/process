package process.pipeline.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import process.model.dto.ResponseDto;
import process.model.enums.ReviewDecision;
import process.security.TenantContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import static process.util.ProcessUtil.SUCCESS;

/**
 * Bulk review (2026-10-12): POST sourceJob.json/review/bulkDecide -- a tenant administrator approves or rejects many runs'
 * internal review at once, and each run is still decided on its own: checked and recorded by a single decision's rules
 * and path ({@link RunReviewService#decideInBulk}), in its own transaction, so one run failing never takes another back.
 * The answer is per run: done, skipped with a reason code, or failed; partial success is normal.
 *
 * <ul>
 *   <li>At most {@value #MAX_ITEMS} distinct runs (duplicates collapsed first); none, or more, is a 400 and nothing is done.</li>
 *   <li>One comment for every run, at most {@value #MAX_COMMENT} characters; a rejection's is required (it is the reason).</li>
 *   <li>{@code bulkId}: the client's (^[A-Za-z0-9-]{8,64}$), else one is made. The same bulkId and run again is done once:
 *       "Already recorded in this bulk decision."</li>
 *   <li>{@code dryRun}: the same checks, nothing written and no event -- the console's confirm panel asks it first.</li>
 *   <li>At most {@value #RATE_CAP} valid bulk requests a rolling minute per caller (dry runs count): a 429. A request that is
 *       not valid is refused (400) before it is counted, as workflow-service and ai-service count theirs. The count is held
 *       in this instance's memory: with more than one Core instance each keeps its own, so a caller could make
 *       {@value #RATE_CAP} per instance (etl-platform docs/capacity.md; a shared store, e.g. Redis, when Core runs on more
 *       than one).</li>
 *   <li>The workspace's review policy ({@link ReviewPolicy}) off: every approve is skipped "bulk-off"; reject stays allowed.</li>
 * </ul>
 */
@Service
public class RunReviewBulk {

    public static final int MAX_ITEMS = 100;
    public static final int MAX_COMMENT = 500;
    public static final int RATE_CAP = 20;
    static final long RATE_WINDOW_MILLIS = 60_000L;
    public static final Pattern BULK_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    static final String TOO_MANY = "Too many bulk decisions in a minute; wait a moment and try again.";

    private static final Logger logger = LoggerFactory.getLogger(RunReviewBulk.class);

    private final RunReviewService reviews;
    private final ReviewPolicy policy;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    @Autowired
    public RunReviewBulk(RunReviewService reviews, ReviewPolicy policy, PlatformTransactionManager transactionManager) {
        this(reviews, policy, transactionManager, System::currentTimeMillis);
    }

    RunReviewBulk(RunReviewService reviews, ReviewPolicy policy, PlatformTransactionManager transactionManager, LongSupplier clock) {
        this.reviews = reviews;
        this.policy = policy;
        // Each run its own transaction, even when a caller holds one: one run's failure never takes another's back.
        this.writes = new TransactionTemplate(transactionManager);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads.setReadOnly(true);
        this.clock = clock;
    }

    /** A request refused as a whole -- nothing was checked or done: its HTTP status and words. */
    public static final class Refused extends RuntimeException {
        private final HttpStatus status;

        Refused(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return this.status;
        }
    }

    /** Decides every run of the request on its own, as the caller; refused as a whole ({@link Refused}) when the request is not valid. */
    public ResponseDto decide(RunReviewBulkRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Reviews belong to a workspace; this account is not attached to one.");
        }
        if (request == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Send jobQueueIds, decision and comment.");
        }
        ReviewDecision decision = decisionOf(request.getDecision());
        Set<Long> ids = new LinkedHashSet<>();
        if (request.getJobQueueIds() != null) {
            request.getJobQueueIds().stream().filter(id -> id != null).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new Refused(HttpStatus.BAD_REQUEST, "List the runs to decide (jobQueueIds).");
        }
        if (ids.size() > MAX_ITEMS) {
            throw new Refused(HttpStatus.BAD_REQUEST, String.format("At most %d runs at once; %d were listed.", MAX_ITEMS, ids.size()));
        }
        String comment = request.getComment() == null || request.getComment().trim().isEmpty() ? null : request.getComment().trim();
        if (decision == ReviewDecision.REJECTED && comment == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "A rejection says why: the comment is required.");
        }
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw new Refused(HttpStatus.BAD_REQUEST, String.format("The comment is at most %d characters.", MAX_COMMENT));
        }
        String bulkId = request.getBulkId() == null || request.getBulkId().trim().isEmpty() ? UUID.randomUUID().toString()
            : request.getBulkId().trim();
        if (!BULK_ID.matcher(bulkId).matches()) {
            throw new Refused(HttpStatus.BAD_REQUEST, "bulkId is 8 to 64 letters, digits and '-'.");
        }
        boolean dryRun = Boolean.TRUE.equals(request.getDryRun());
        // Valid: now it counts against the caller's cap (a refused request never used one up).
        this.countAgainstTheCap();
        boolean bulkApprove = decision != ReviewDecision.APPROVED || this.reads.execute(status -> this.policy.bulkApproveRuns(tenantId));

        List<Map<String, Object>> done = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                RunReviewService.BulkOutcome outcome = (dryRun ? this.reads : this.writes).execute(status -> this.reviews.decideInBulk(id,
                    decision, comment, bulkId, dryRun, bulkApprove));
                Map<String, Object> item = item(id, outcome.label, outcome.group, outcome.link);
                if (outcome.done) {
                    item.put("message", outcome.message);
                    done.add(item);
                } else {
                    item.put("reason", outcome.reason);
                    item.put("message", outcome.message);
                    skipped.add(item);
                }
            } catch (RuntimeException failure) {
                logger.warn("Bulk decision {} by {} (user {}): run {} could not be recorded; the other runs go on.", bulkId,
                    TenantContext.getUsername(), TenantContext.getAppUserId(), id, failure);
                String[] about = this.describe(id);
                Map<String, Object> item = item(id, about == null ? null : about[0], about == null ? null : about[1],
                    about == null ? null : about[2]);
                item.put("message", "Could not be recorded: " + causeOf(failure) + ".");
                failed.add(item);
            }
        }
        String word = decision == ReviewDecision.APPROVED ? "approved" : "rejected";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bulkId", bulkId);
        data.put("action", decision == ReviewDecision.APPROVED ? "approve" : "reject");
        data.put("dryRun", dryRun);
        data.put("requested", ids.size());
        data.put("done", done);
        data.put("skipped", skipped);
        data.put("failed", failed);
        String message = String.format("%d %s%s, %d skipped", done.size(), dryRun ? "would be " : "", word, skipped.size())
            + (failed.isEmpty() ? "" : String.format(", %d failed", failed.size())) + ".";
        if (!dryRun) {
            logger.info("Bulk decision {} of workspace {} by {} (user {}): {} runs {} -- {}", bulkId, tenantId, TenantContext.getUsername(),
                TenantContext.getAppUserId(), ids.size(), word, message);
        }
        return new ResponseDto(SUCCESS, message, data);
    }

    /** One more valid bulk request by the caller in the rolling minute, on this instance; a 429 past {@value #RATE_CAP}. */
    private void countAgainstTheCap() {
        String caller = TenantContext.getAppUserId() != null ? "user:" + TenantContext.getAppUserId()
            : "name:" + TenantContext.getUsername();
        long now = this.clock.getAsLong();
        Deque<Long> times = this.recent.computeIfAbsent(caller, key -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - RATE_WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= RATE_CAP) {
                throw new Refused(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
            }
            times.addLast(now);
        }
        if (this.recent.size() > 10_000) {
            // Callers gone quiet: their windows are long past.
            this.recent.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || entry.getValue().peekLast() <= now - RATE_WINDOW_MILLIS;
                }
            });
        }
    }

    /** A failed run's label, group and link, best-effort: null when it cannot be read either. */
    private String[] describe(long id) {
        try {
            return this.reads.execute(status -> this.reviews.describe(id));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Map<String, Object> item(long id, String label, String group, String link) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("label", label);
        item.put("group", group);
        item.put("link", link);
        return item;
    }

    /** A short cause a person can read -- never the database's own words. */
    private static String causeOf(RuntimeException failure) {
        if (failure instanceof DataAccessException) {
            return "the database refused the change";
        }
        return "an internal error (logged)";
    }

    private static ReviewDecision decisionOf(String word) {
        String upper = word == null ? "" : word.trim().toUpperCase(Locale.ROOT);
        if ("APPROVED".equals(upper) || "APPROVE".equals(upper)) {
            return ReviewDecision.APPROVED;
        }
        if ("REJECTED".equals(upper) || "REJECT".equals(upper)) {
            return ReviewDecision.REJECTED;
        }
        throw new Refused(HttpStatus.BAD_REQUEST, "decision is APPROVED or REJECTED.");
    }
}
