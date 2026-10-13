package process.backfill;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.BulkAction;
import process.engine.ProducerBulkEngine;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.notifications.NotificationPort;
import process.notifications.Notices;
import process.pipeline.RunParameters;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * MIG-347: the backfills' dispatcher, every {@value #TICK_SECONDS} seconds under ShedLock (one replica a tick).
 *
 * <ol>
 *   <li>Every period whose run has ended takes its run's outcome (COMPLETED or FAILED) -- one statement, every
 *   workspace's.</li>
 *   <li>Each open backfill, as its own workspace and in its own transaction (its row locked, skipped when a person holds
 *   it): a schedule deleted cancels it; a workspace paused (Suspended, Inactive) or a schedule switched off holds it, said
 *   on the backfill; a paused backfill queues nothing. Otherwise its next periods are queued, oldest first, as many as its
 *   concurrency leaves room for -- and the platform's cap on backfill runs in flight (process.backfill.max-in-flight) --
 *   each in a free lane of the job (1 to 4: V211's one-in-flight index per job and lane). A period that already has a
 *   completed run is skipped unless the backfill re-runs completed periods. So the runs are queued gradually: a few each
 *   tick, never the whole range.</li>
 *   <li>A backfill with nothing waiting and nothing in flight is closed: COMPLETED, or FAILED with a failed period; whoever
 *   started it hears of it in the bell.</li>
 * </ol>
 * The runs then go the way of any run: pre-dispatch, the step engine, the worker callback's rules, the meters. Off with
 * process.backfill.enabled=false (and with every cron, process.scheduling.enabled=false).
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class BackfillDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(BackfillDispatcher.class);

    public static final int TICK_SECONDS = 10;
    /** Periods looked at past the free lanes in one pass: skipped ones cost no lane. */
    static final int LOOK_AHEAD = 50;

    private final BackfillStore store;
    private final BackfillSettings settings;
    private final TransactionTemplate tx;
    private final Function<Long, Optional<String>> workspacePause;
    private final BiConsumer<Long, String> audit;
    private final NotificationPort notifications;
    private final boolean enabled;

    @Autowired
    public BackfillDispatcher(BackfillStore store, BackfillSettings settings, PlatformTransactionManager transactions, ProducerBulkEngine engine,
        BulkAction bulkAction, NotificationPort notifications, @Value("${process.backfill.enabled:true}") boolean enabled) {
        this(store, settings, new TransactionTemplate(transactions), engine::workspacePause, bulkAction::saveJobAuditLogs, notifications, enabled);
    }

    BackfillDispatcher(BackfillStore store, BackfillSettings settings, TransactionTemplate tx, Function<Long, Optional<String>> workspacePause,
        BiConsumer<Long, String> audit, NotificationPort notifications, boolean enabled) {
        this.store = store;
        this.settings = settings;
        this.tx = tx;
        this.workspacePause = workspacePause;
        this.audit = audit;
        this.notifications = notifications;
        this.enabled = enabled;
    }

    /** What one pass did. */
    public static final class Pass {
        public int settled;
        public int queued;
        public int skipped;
        public int held;
        public int finished;
        public int failedBackfills;
        int platformInFlight;

        @Override
        public String toString() {
            return String.format("settled %d, queued %d, skipped %d, held %d, finished %d, errors %d", this.settled, this.queued, this.skipped,
                this.held, this.finished, this.failedBackfills);
        }
    }

    @Scheduled(fixedDelayString = "${process.backfill.tick-ms:10000}", initialDelayString = "${process.backfill.initial-delay-ms:30000}")
    @SchedulerLock(name = "backfillDispatch", lockAtLeastFor = "5S", lockAtMostFor = "60S")
    public void scheduled() {
        if (!this.enabled) {
            return;
        }
        try {
            Pass pass = this.tick();
            if (pass.queued + pass.settled + pass.finished + pass.failedBackfills > 0) {
                logger.info("Backfills: {}.", pass);
            }
        } catch (RuntimeException failed) {
            logger.warn("The backfill dispatcher's pass failed: {}", failed.getMessage());
        }
    }

    /** One pass over every open backfill. */
    public Pass tick() {
        Pass pass = new Pass();
        List<long[]> settled = RowSecurity.acrossTenants("the backfill dispatcher (MIG-347) records on every workspace's backfill periods the "
            + "outcome of their runs that ended", () -> this.tx.execute(status -> this.store.settleEndedRuns()));
        pass.settled = settled == null ? 0 : settled.size();
        List<long[]> open = RowSecurity.acrossTenants("the backfill dispatcher (MIG-347) reads which backfills of every workspace are open "
            + "(ids only); each is then worked as its own workspace", this.store::open);
        pass.platformInFlight = RowSecurity.acrossTenants("the backfill dispatcher (MIG-347) counts the backfill runs in flight on the "
            + "platform (a number), the cap every workspace's backfills share", this.store::inFlightEverywhere);
        for (long[] backfill : open) {
            try {
                RowSecurity.forTenant(backfill[1], () -> this.tx.execute(status -> {
                    this.work(backfill[0], pass);
                    return null;
                }));
            } catch (RuntimeException failed) {
                pass.failedBackfills++;
                logger.warn("Backfill {}: this pass could not work it: {}", backfill[0], failed.getMessage());
            }
        }
        return pass;
    }

    /** One open backfill, in its workspace's transaction. */
    void work(long backfillId, Pass pass) {
        Optional<BackfillStore.Backfill> locked = this.store.lock(backfillId, true);
        if (!locked.isPresent() || !locked.get().isOpen()) {
            return;
        }
        BackfillStore.Backfill b = locked.get();
        Optional<BackfillStore.Job> job = this.store.job(b.jobId);
        if (!job.isPresent() || "Delete".equals(job.get().status)) {
            this.store.move(b.id, Backfills.OPEN, "CANCELLED", null);
            this.store.cancelPending(b.id);
            this.store.hold(b.id, "Cancelled: its schedule was deleted.");
            pass.finished++;
            this.tell(b, "CANCELLED");
            return;
        }
        if (b.counts.pending == 0) {
            this.finishIfDone(b, pass);
            return;
        }
        if ("PAUSED".equals(b.status)) {
            return;
        }
        Optional<String> paused = this.workspacePause.apply(b.tenantId);
        String hold = paused.isPresent() ? "Waiting: the workspace is " + paused.get() + ", so its runs are paused until it is Active again."
            : !"Active".equals(job.get().status) ? "Waiting: the schedule is switched off; its periods are queued once it is switched on again."
            : null;
        this.store.hold(b.id, hold);
        if (hold != null) {
            pass.held++;
            return;
        }
        int free = Math.min(b.concurrency - this.store.inFlight(b.id), this.settings.maxInFlight() - pass.platformInFlight);
        if (free <= 0) {
            return;
        }
        Set<Integer> lanes = this.store.lanesInUse(b.jobId);
        Map<String, String> params = RunParameters.read(b.params).params;
        ZoneId zone = ZoneId.of(b.zone);
        int position = b.counts.done() + b.counts.queued;
        for (BackfillStore.Slot slot : this.store.pending(b.id, free + LOOK_AHEAD)) {
            if (free == 0) {
                break;
            }
            if (!b.rerunCompleted && this.store.covered(b.jobId, slot.start, slot.end)) {
                this.store.skip(slot, "Skipped: a completed run of this period already exists.");
                pass.skipped++;
                position++;
                continue;
            }
            int lane = freeLane(lanes);
            if (lane == 0) {
                break;
            }
            RunParameters.RunValues values = RunParameters.RunValues.ofPeriod(params, slot.date, slot.start, slot.end, zone);
            long run = this.store.queueRun(job.get(), b, slot, lane, values.toJson(), CorrelationId.generate(),
                String.format("Job %d now in the queue: backfill #%d, %s.", b.jobId, b.id, slot.date));
            position++;
            this.audit.accept(run, String.format("Job %d now in the queue: backfill #%d, period %d of %d -- %s (%s to %s, %s), lane %d.", b.jobId,
                b.id, position, b.total, slot.date, values.windowStart, values.windowEnd, b.zone, lane));
            lanes.add(lane);
            free--;
            pass.queued++;
            pass.platformInFlight++;
        }
        // Every period left was skipped: nothing will settle it later.
        BackfillStore.Backfill after = this.store.find(b.id).orElse(b);
        if (after.counts.pending == 0) {
            this.finishIfDone(after, pass);
        }
    }

    private static int freeLane(Set<Integer> used) {
        for (int lane = 1; lane <= BackfillSettings.LANES; lane++) {
            if (!used.contains(lane)) {
                return lane;
            }
        }
        return 0;
    }

    private void finishIfDone(BackfillStore.Backfill b, Pass pass) {
        Optional<String> closed = this.store.finishIfDone(b.id);
        if (closed.isPresent()) {
            pass.finished++;
            this.store.hold(b.id, null);
            this.tell(this.store.find(b.id).orElse(b), closed.get());
        }
    }

    /** The bell for whoever started it (an API client's backfill has no one to tell). */
    private void tell(BackfillStore.Backfill b, String how) {
        if (this.notifications == null || b.createdBy == null) {
            return;
        }
        try {
            NotificationSeverity severity = "COMPLETED".equals(how) ? NotificationSeverity.SUCCESS : NotificationSeverity.WARNING;
            String title = String.format("Backfill #%d of %s %s", b.id, b.jobName == null ? "schedule " + b.jobId : b.jobName,
                "COMPLETED".equals(how) ? "finished" : "FAILED".equals(how) ? "finished with failures" : "was cancelled");
            String body = String.format("%d of %d periods completed, %d failed, %d skipped.", b.counts.completed, b.total, b.counts.failed,
                b.counts.skipped);
            this.notifications.notificationCreated(b.tenantId, Notices.notice(b.createdBy, NotificationType.BATCH_DONE, severity,
                Backfills.cut(title, 300), body, "/runs/backfills?backfill=" + b.id));
        } catch (RuntimeException unsent) {
            logger.warn("Backfill {}: its notice could not be sent: {}", b.id, unsent.getMessage());
        }
    }
}
