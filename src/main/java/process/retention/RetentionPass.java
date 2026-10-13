package process.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MIG-354: one retention pass over one workspace -- the daily one, an administrator's "Run now", or the continuation of
 * one that stopped. The caller runs it as the workspace (RowSecurity.forTenant); every statement here and in the targets
 * is then the workspace's alone.
 *
 * <p>Per class, in a fixed order, each in its own small transactions, one failing never stopping the next:
 * <ol>
 *   <li>forever (no days): OFF; a workspace hold: HELD -- nothing is read or removed;</li>
 *   <li>the cutoff: now minus the class's days; the audit class's never under the platform's floor;</li>
 *   <li>the preview (count, total, oldest): nothing eligible is DONE; more than the platform's share of the class
 *   (20% by default) without an administrator's confirmation of about that many is PAUSED, and nothing is removed -- the
 *   workspace's RETENTION alert tells its administrators;</li>
 *   <li>batches of {@link #BATCH}, oldest first, until nothing is eligible (DONE, the confirmation spent), the class's
 *   {@link #MAX_PER_CLASS} for this pass or the pass's time budget is reached (PARTIAL: the scheduler's next tick
 *   resumes it), or a batch fails (FAILED, with the reason).</li>
 * </ol>
 * The pass's record (retention_pass, retention_pass_class) is the audit of what it did: per class the cutoff, eligible,
 * removed, bytes and how it ended; retention_audit has a line at its start and one at its end with the counts.
 */
@Component
public class RetentionPass {

    static final int BATCH = 500;
    static final long MAX_PER_CLASS = 50_000;
    /** Pipeline-held runs a remote class can be told to keep; more than this and the class waits (HELD) instead. */
    static final int MAX_KEEP_RUNS = 5_000;
    /** Confirmed "about N": a few more items may have aged past the cutoff since the administrator looked. */
    static final double CONFIRM_SLACK = 1.10;
    static final int CONFIRM_SLACK_MIN = 10;

    /** The order classes are taken in: Core's own first, the other services' after, the audit last (it records the rest). */
    static final DataClass[] ORDER = {DataClass.RUN_OUTPUTS, DataClass.RUN_LOGS, DataClass.FORM_SUBMISSIONS, DataClass.FILES,
        DataClass.DOCUMENTS, DataClass.AI_CONVERSATIONS, DataClass.NOTIFICATIONS, DataClass.AUDIT};

    private static final Logger logger = LoggerFactory.getLogger(RetentionPass.class);

    private final RetentionStore store;
    private final CoreRetention core;
    private final RemoteRetention remote;
    private final Clock clock;
    private final Duration budget;

    @Autowired
    public RetentionPass(RetentionStore store, CoreRetention core, RemoteRetention remote,
        @Value("${process.retention.pass-budget-minutes:20}") int budgetMinutes) {
        this(store, core, remote, Clock.systemUTC(), Duration.ofMinutes(Math.max(1, budgetMinutes)));
    }

    public RetentionPass(RetentionStore store, CoreRetention core, RemoteRetention remote, Clock clock, Duration budget) {
        this.store = store;
        this.core = core;
        this.remote = remote;
        this.clock = clock;
        this.budget = budget;
    }

    /** What a pass did, for the caller and the tests. */
    public static final class Outcome {
        public Long passId;
        public String state;
        public long deleted;
        public long bytes;
        public String note;
        public final List<RetentionStore.ClassResult> classes = new ArrayList<>();

        static Outcome busy() {
            Outcome outcome = new Outcome();
            outcome.state = "BUSY";
            outcome.note = "A retention pass is already running in this workspace.";
            return outcome;
        }
    }

    /** The class's data, wherever it lives. */
    RetentionTarget target(DataClass dataClass) {
        return dataClass.owner() == DataClass.Owner.CORE ? this.core.target(dataClass) : this.remote.target(dataClass);
    }

    /** The cutoff a class's days give now; the audit class's never under the floor. Null: forever. */
    static Instant cutoff(DataClass dataClass, Integer keepDays, int auditFloorDays, Instant now) {
        if (keepDays == null) {
            return null;
        }
        int days = dataClass == DataClass.AUDIT ? Math.max(keepDays, auditFloorDays) : keepDays;
        return now.minus(Duration.ofDays(days));
    }

    /** True when removing {@code eligible} of {@code total} needs an administrator's confirmation first. */
    static boolean overShare(long eligible, long total, int maxSharePct) {
        return total > 0 && eligible * 100 > (long) maxSharePct * total;
    }

    /** True when the confirmation given covers what is eligible now. */
    static boolean confirmed(RetentionStore.Rule rule, long eligible) {
        if (rule == null || rule.confirmedCount == null) {
            return false;
        }
        long allowed = Math.max((long) Math.ceil(rule.confirmedCount * CONFIRM_SLACK), rule.confirmedCount + CONFIRM_SLACK_MIN);
        return eligible <= allowed;
    }

    /** Runs a pass of the workspace now. how: SCHEDULE, MANUAL or RESUME; by: the administrator (null for the scheduler). */
    public Outcome run(long tenantId, String how, Long by) {
        Optional<Long> started = this.store.startPass(tenantId, how, by);
        if (!started.isPresent()) {
            return Outcome.busy();
        }
        return this.execute(tenantId, started.get(), how, by);
    }

    /** Runs a pass already started (its RUNNING row made, e.g. by an administrator's request) to its end. */
    public Outcome execute(long tenantId, long passId, String how, Long by) {
        Outcome outcome = new Outcome();
        outcome.passId = passId;
        Instant began = this.clock.instant();
        Instant deadline = began.plus(this.budget);
        try {
            RetentionStore.Policy policy = this.store.policy(tenantId);
            RetentionStore.Platform platform = this.store.platform();
            Holds holds = this.store.activeHolds(tenantId);
            this.store.audit(tenantId, by, "PASS_STARTED", null, passId, "Retention pass " + passId + " started (" + how.toLowerCase()
                + ")" + (holds.workspace ? "; the workspace is on legal hold, so nothing is removed." : holds.any() ? "; "
                + (holds.jobIds.size() + holds.runIds.size()) + " pipeline or run hold(s) apply." : "."));
            boolean partial = false;
            boolean failed = false;
            for (DataClass dataClass : ORDER) {
                RetentionStore.ClassResult result = this.runClass(tenantId, passId, dataClass, policy, platform, holds, deadline);
                outcome.classes.add(result);
                outcome.deleted += result.deleted;
                outcome.bytes += result.bytes;
                partial |= "PARTIAL".equals(result.state);
                failed |= "FAILED".equals(result.state);
                this.store.heartbeat(passId);
            }
            outcome.state = partial ? "PARTIAL" : failed ? "FAILED" : "DONE";
            outcome.note = summary(outcome);
        } catch (RuntimeException broke) {
            logger.warn("Retention pass {} of workspace {} failed: {}", passId, tenantId, broke.getMessage(), broke);
            outcome.state = "FAILED";
            outcome.note = "The pass stopped: " + broke.getMessage();
        }
        this.store.finishPass(passId, outcome.state, outcome.deleted, outcome.bytes, outcome.note);
        this.store.audit(tenantId, by, "PASS_FINISHED", null, passId, "Retention pass " + passId + " " + outcome.state.toLowerCase() + ": "
            + outcome.note);
        logger.info("Retention pass {} of workspace {}: {} -- {}", passId, tenantId, outcome.state, outcome.note);
        return outcome;
    }

    RetentionStore.ClassResult runClass(long tenantId, long passId, DataClass dataClass, RetentionStore.Policy policy,
        RetentionStore.Platform platform, Holds holds, Instant deadline) {
        RetentionStore.Rule rule = policy.rules.get(dataClass);
        Integer days = rule == null ? null : rule.keepDays;
        Instant now = this.clock.instant();
        Instant cutoff = cutoff(dataClass, days, platform.auditFloorDays, now);
        RetentionStore.ClassResult result = new RetentionStore.ClassResult();
        result.dataClass = dataClass;
        result.keepDays = days;
        result.cutoff = cutoff;
        this.store.startClass(passId, tenantId, dataClass, days, cutoff);
        if (cutoff == null) {
            return this.finish(passId, result, "OFF", "Kept forever.");
        }
        if (holds.workspace) {
            return this.finish(passId, result, "HELD", "The workspace is on legal hold.");
        }
        Holds applied;
        try {
            applied = this.holdsFor(tenantId, dataClass, holds, cutoff);
        } catch (TooManyHeld tooMany) {
            return this.finish(passId, result, "HELD", tooMany.getMessage());
        }
        RetentionTarget target = this.target(dataClass);
        try {
            RetentionTarget.Tally tally = target.preview(tenantId, cutoff, applied);
            result.eligible = tally.count;
            result.total = tally.total;
            result.oldest = tally.oldest;
            this.store.classTally(passId, dataClass, tally.count, tally.total, tally.oldest);
            if (tally.count == 0) {
                this.store.clearConfirmation(tenantId, dataClass);
                return this.finish(passId, result, "DONE", "Nothing older than " + days + " day(s).");
            }
            if (overShare(tally.count, tally.total, platform.maxSharePct) && !confirmed(rule, tally.count)) {
                this.store.audit(tenantId, null, "CLASS_PAUSED", dataClass, passId, dataClass.label() + ": " + tally.count + " of " + tally.total
                    + " item(s) are older than " + days + " day(s) -- more than " + platform.maxSharePct + "% in one pass, so nothing was removed "
                    + "until an administrator confirms.");
                return this.finish(passId, result, "PAUSED", tally.count + " of " + tally.total + " item(s) would go, more than "
                    + platform.maxSharePct + "% in one pass. An administrator confirms in Data policies › Retention, then it continues.");
            }
            boolean more = true;
            while (more && result.deleted < MAX_PER_CLASS && this.clock.instant().isBefore(deadline)) {
                int limit = (int) Math.min(BATCH, MAX_PER_CLASS - result.deleted);
                RetentionTarget.Batch batch = target.purge(tenantId, cutoff, applied, limit, passId);
                result.deleted += batch.deleted;
                result.bytes += batch.bytes;
                more = batch.more && batch.deleted > 0;
                this.store.classProgress(passId, dataClass, result.deleted, result.bytes);
                this.store.heartbeat(passId);
            }
            if (more) {
                return this.finish(passId, result, "PARTIAL", "Removed " + result.deleted + "; the rest continues in the next pass.");
            }
            this.store.clearConfirmation(tenantId, dataClass);
            return this.finish(passId, result, "DONE", "Removed " + result.deleted + " item(s) older than " + days + " day(s).");
        } catch (RetentionTarget.Unavailable unavailable) {
            return this.finish(passId, result, "UNAVAILABLE", unavailable.getMessage());
        } catch (RuntimeException failed) {
            logger.warn("Retention pass {}: workspace {}'s {} failed after {} removed: {}", passId, tenantId, dataClass, result.deleted,
                failed.getMessage());
            return this.finish(passId, result, "FAILED", "Stopped after " + result.deleted + " removed: " + failed.getMessage());
        }
    }

    private RetentionStore.ClassResult finish(long passId, RetentionStore.ClassResult result, String state, String note) {
        result.state = state;
        result.note = note;
        this.store.classProgress(passId, result.dataClass, result.deleted, result.bytes);
        this.store.finishClass(passId, result.dataClass, state, note);
        return result;
    }

    /** Thrown when the held pipelines have more old runs than a remote class can be told to keep. */
    static final class TooManyHeld extends Exception {
        TooManyHeld(String message) {
            super(message);
        }
    }

    /**
     * The holds as the class applies them. Core's classes read pipeline and run holds in their own statements; a class
     * another service holds knows only runs, so a pipeline hold becomes the pipeline's runs made before the cutoff (at
     * most {@link #MAX_KEEP_RUNS}; more and the class is HELD this pass rather than risk one).
     */
    Holds holdsFor(long tenantId, DataClass dataClass, Holds holds, Instant cutoff) throws TooManyHeld {
        if (!dataClass.knowsRuns() || dataClass.owner() == DataClass.Owner.CORE || holds.jobIds.isEmpty()) {
            return holds;
        }
        List<Long> runs = this.store.heldPipelineRuns(tenantId, holds.jobIds, cutoff, MAX_KEEP_RUNS);
        if (runs.size() > MAX_KEEP_RUNS) {
            throw new TooManyHeld("The held pipelines have more than " + MAX_KEEP_RUNS + " runs older than the cutoff; this class waits while "
                + "the hold is on.");
        }
        Set<Long> keep = new LinkedHashSet<>(holds.runIds);
        keep.addAll(runs);
        return new Holds(false, holds.jobIds, keep);
    }

    static String summary(Outcome outcome) {
        StringBuilder out = new StringBuilder();
        out.append("removed ").append(outcome.deleted).append(" item(s)");
        if (outcome.bytes > 0) {
            out.append(" (").append(outcome.bytes).append(" bytes)");
        }
        List<String> parts = new ArrayList<>();
        for (RetentionStore.ClassResult c : outcome.classes) {
            if ("OFF".equals(c.state)) {
                continue;
            }
            parts.add(c.dataClass.name().toLowerCase() + " " + c.state.toLowerCase() + (c.deleted > 0 ? " " + c.deleted : ""));
        }
        if (!parts.isEmpty()) {
            out.append("; ").append(String.join(", ", parts));
        }
        return out.append('.').toString();
    }

    /** For the console's preview: each class as a pass would see it now (no record is written). */
    public Map<DataClass, RetentionStore.ClassResult> preview(long tenantId, Map<DataClass, Integer> days, Map<DataClass, RetentionStore.Rule> rules,
        RetentionStore.Platform platform, Holds holds) {
        Map<DataClass, RetentionStore.ClassResult> out = new java.util.EnumMap<>(DataClass.class);
        Instant now = this.clock.instant();
        for (DataClass dataClass : ORDER) {
            RetentionStore.ClassResult result = new RetentionStore.ClassResult();
            result.dataClass = dataClass;
            result.keepDays = days.get(dataClass);
            result.cutoff = cutoff(dataClass, result.keepDays, platform.auditFloorDays, now);
            out.put(dataClass, result);
            if (result.cutoff == null) {
                result.state = "OFF";
                continue;
            }
            try {
                Holds applied = this.holdsFor(tenantId, dataClass, holds.workspace ? new Holds(false, holds.jobIds, holds.runIds) : holds,
                    result.cutoff);
                RetentionTarget.Tally tally = this.target(dataClass).preview(tenantId, result.cutoff, applied);
                result.eligible = tally.count;
                result.total = tally.total;
                result.oldest = tally.oldest;
                result.bytes = tally.bytes == null ? -1 : tally.bytes;
                result.state = holds.workspace ? "HELD" : tally.count == 0 ? "NOTHING" : overShare(tally.count, tally.total, platform.maxSharePct)
                    && !confirmed(rules.get(dataClass), tally.count) ? "NEEDS_CONFIRMATION" : "READY";
            } catch (TooManyHeld tooMany) {
                result.state = "HELD";
                result.note = tooMany.getMessage();
            } catch (RetentionTarget.Unavailable unavailable) {
                result.state = "UNAVAILABLE";
                result.note = unavailable.getMessage();
            } catch (RuntimeException failed) {
                result.state = "FAILED";
                result.note = failed.getMessage();
            }
        }
        return out;
    }
}
