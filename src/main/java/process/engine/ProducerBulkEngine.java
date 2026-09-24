package process.engine;

import process.util.BusinessTime;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.barco.platform.correlation.CorrelationId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import process.notifications.JobMail;
import process.outbox.DispatchOutbox;
import process.outbox.DispatchOutcomes;
import process.engine.dto.JobPayloadDTO;
import process.security.RunCallbackTokens;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import static java.util.Objects.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class ProducerBulkEngine implements DispatchOutcomes {

    public Logger logger = LogManager.getLogger(ProducerBulkEngine.class);

    private final RunCallbackTokens runCallbackTokens;
    private final BulkAction bulkAction;
    private final TransactionServiceImpl transactionService;
    private final JobMail jobMail;
    private final DispatchFailures failures;
    /** Where a dispatched run's hand-off is written, to be published after commit (MIG-136). */
    private final DispatchOutbox dispatchOutbox;
    /** One local transaction per enqueued slot and per dispatched run; none for an engine built by hand in a test. */
    private final TransactionOperations transactions;
    /** The dispatch pass's clock and pause, so the budget arithmetic can be tested without waiting minutes. */
    private LongSupplier clock = System::currentTimeMillis;
    private LongConsumer pause = ProducerBulkEngine::sleep;

    public ProducerBulkEngine(BulkAction bulkAction,
        TransactionServiceImpl transactionService,
        JobMail jobMail,
        RunCallbackTokens runCallbackTokens,
        DispatchOutbox dispatchOutbox) {
        this(bulkAction, transactionService, jobMail, runCallbackTokens, dispatchOutbox,
            TransactionOperations.withoutTransaction());
    }

    @Autowired
    public ProducerBulkEngine(BulkAction bulkAction,
        TransactionServiceImpl transactionService,
        JobMail jobMail,
        RunCallbackTokens runCallbackTokens,
        DispatchOutbox dispatchOutbox,
        PlatformTransactionManager transactionManager) {
        this(bulkAction, transactionService, jobMail, runCallbackTokens, dispatchOutbox, rowTransactions(transactionManager));
    }

    ProducerBulkEngine(BulkAction bulkAction,
        TransactionServiceImpl transactionService,
        JobMail jobMail,
        RunCallbackTokens runCallbackTokens,
        DispatchOutbox dispatchOutbox,
        TransactionOperations transactions) {
        this.transactions = transactions;
        this.runCallbackTokens = runCallbackTokens;
        this.bulkAction = bulkAction;
        this.transactionService = transactionService;
        this.jobMail = jobMail;
        this.dispatchOutbox = dispatchOutbox;
        this.failures = new DispatchFailures(bulkAction, transactionService, jobMail, transactions);
    }

    /** Bounded, so one row can never take longer than DispatchTiming allows for it. */
    private static TransactionTemplate rowTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(DispatchTiming.ROW_TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    /** For the timing tests: a fake clock, and a pause that advances it instead of sleeping. */
    void useClock(LongSupplier clock, LongConsumer pause) {
        this.clock = clock;
        this.pause = pause;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public void addManualJobInQueue(SourceJob sourceJob) {
        this.bulkAction.changeJobStatus(sourceJob.getJobId(), JobStatus.Queue);
        JobQueue jobQueue = this.bulkAction.createJobQueueV1(sourceJob.getJobId(),
            BusinessTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
        this.bulkAction.changeJobLastJobRun(sourceJob.getJobId(), jobQueue.getStartTime());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", sourceJob.getJobId()));
        this.bulkAction.sendJobStatusNotification(sourceJob.getJobId());
    }

    public void skipManualJobInQueue(Scheduler scheduler) {

        JobQueue jobQueue = this.bulkAction.createJobQueueV1(scheduler.getJobId(),
            scheduler.getNextRunAt(), JobStatus.Skip, "Job %s skip, by user action.", true);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, by user action.", scheduler.getJobId()));

        /*
         * The live event, but not a fresh outcome announcement.
         *
         * The one-argument overload means "a new outcome transition just happened", and
         * notifyJobOutcome then reads source_job.job_running_status to decide what to announce.
         * A skip changes no such thing: it writes a Skip row against the run that was due and
         * leaves the job's running status holding the PREVIOUS run's outcome. So skipping a job
         * whose last run had finished raised a second "Job completed -- <name> finished
         * successfully." in the notification centre, and skipping one whose last run had failed
         * raised a second "Job failed", each dated to the moment the operator chose NOT to run
         * it. The browser still needs the state push, which is what the other argument keeps.
         */
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId(), false);
        Optional<SourceJob> sourceJobForSkipMail = this.transactionService.findByJobId(jobQueue.getJobId());
        if (sourceJobForSkipMail.isPresent() && sourceJobForSkipMail.get().isSkipJob()) {
            this.jobMail.send(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Skip);
        }
    }

    /**
     * How long a run may claim to be going before it is treated as dead.
     *
     * Measured against this environment: half of all runs finish inside three minutes and 99%
     * inside thirty, with one five-hour outlier. Six hours is twelve times the 99th percentile
     * and well past anything observed, because being wrong here costs a run that was still
     * working -- while leaving a stranded run in place costs the job every future run it had.
     */
    private static final long STALLED_AFTER_MINUTES = 6 * 60;

    /**
     * Closes runs whose worker is never coming back.
     *
     * A worker that finishes its work and then cannot report -- a server restart mid-callback, a
     * dropped connection -- leaves its row in Start for ever. The dispatcher counts anything in
     * Queue, Start or Running when deciding whether a job is already busy, so a single stranded
     * run quietly stops that job being scheduled again: it collects "already in queue" skips
     * instead of running, and nothing says why.
     *
     * They are marked Interrupt rather than Completed or Failed on purpose. What the worker
     * managed before it went quiet is not knowable from here, and a run recorded as finished
     * when nobody knows whether it did is worse than one recorded as interrupted.
     */
    public void reconcileStalledRuns() {
        try {
            LocalDateTime cutoff = BusinessTime.now().minusMinutes(STALLED_AFTER_MINUTES);
            // Two ways a run is known to be over without its worker saying so: six hours of silence, or
            // (MIG-63) a report from its own worker refused because the token had expired -- proof the
            // worker is alive and can no longer be heard, so there is no reason to wait out the six
            // hours with the job blocked. A run on both lists is closed once, for the refusal, which
            // is the more specific reason.
            Map<Long, JobQueue> toClose = new LinkedHashMap<>();
            for (JobQueue jobQueue : this.transactionService.findStalledRuns(cutoff)) {
                toClose.put(jobQueue.getJobQueueId(), jobQueue);
            }
            int silent = toClose.size();
            for (JobQueue jobQueue : this.transactionService.findRunsWithRefusedCallbacks()) {
                toClose.put(jobQueue.getJobQueueId(), jobQueue);
            }
            if (toClose.isEmpty()) {
                return;
            }
            if (silent > 0) {
                logger.warn("reconcileStalledRuns --> {} run(s) have been in flight since before {}; "
                    + "closing them so their jobs can be scheduled again.", silent, cutoff);
            }
            for (JobQueue jobQueue : toClose.values()) {
                try {
                    boolean refused = jobQueue.getRefusedCallbackAt() != null;
                    String reported = "log".equals(jobQueue.getRefusedCallbackStatus())
                        || jobQueue.getRefusedCallbackStatus() == null ? "a log line" : jobQueue.getRefusedCallbackStatus();
                    jobQueue.setJobStatus(JobStatus.Interrupt);
                    jobQueue.setEndTime(BusinessTime.now());
                    jobQueue.setJobStatusMessage(refused
                        ? String.format("Job %s's worker reported %s at %s, but its callback token had expired, "
                            + "so the report was refused. Closed as interrupted -- check the output before "
                            + "running it again.", jobQueue.getJobId(), reported, jobQueue.getRefusedCallbackAt())
                        : String.format(
                        "Job %s stopped reporting and was closed after %d hours. Its worker may "
                        + "have finished the work -- check the output before running it again.",
                        jobQueue.getJobId(), STALLED_AFTER_MINUTES / 60));
                    this.transactionService.saveJobQueue(jobQueue);
                    // Quote the time it actually has. A run that was never dispatched has no
                    // start_time, and "no update since null" reads as "we lost track of it" when
                    // what happened is "it was never picked up" -- two different incidents, told
                    // apart here or nowhere.
                    this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), refused
                        ? String.format("Run closed automatically: the worker's report (%s) at %s was refused "
                            + "because its callback token had expired.",
                            reported, jobQueue.getRefusedCallbackAt())
                        : jobQueue.getStartTime() != null
                        ? String.format("Run closed automatically: no update from the worker since %s.",
                            jobQueue.getStartTime())
                        : String.format("Run closed automatically: queued at %s and never picked up.",
                            BusinessTime.legacyText(jobQueue.getDateCreated())));
                    // The job carries its own copy of the running status, and that is what the
                    // console shows. Closing the queue row alone leaves the job reading Start for
                    // ever -- the same symptom, one table across. Only clear it once the job has
                    // genuinely nothing in flight, so a newer run that did start is left alone.
                    if (this.bulkAction.getCountForInQueueJobByJobId(jobQueue.getJobId()) == 0) {
                        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Interrupt);
                    }
                    this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
                } catch (Exception ex) {
                    logger.error("Error closing stalled run {}: {}.", jobQueue.getJobQueueId(),
                        ExceptionUtil.getRootCauseMessage(ex));
                }
            }
        } catch (Exception ex) {
            logger.error("Error in reconcileStalledRuns: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * The enqueuer: claims due slots one at a time until none is left (MIG-152).
     *
     * Each slot is its own local transaction: claim the scheduler row (FOR UPDATE SKIP LOCKED), enqueue
     * or skip, advance next_run_at -- together or not at all. Any number of replicas run this at once
     * with no ShedLock and no coordinator, each on slots no other holds, and a replica that dies part-way
     * leaves its slot exactly as it was for the next tick to enqueue once. (A claim that advanced the
     * cursor in a statement of its own, before the work, would have lost the slot instead.)
     *
     * The run row, not the busy count, is the last word (P12): if another enqueuer or a Run now takes the
     * job between the count and the insert, the index refuses this insert at commit and the whole slot
     * rolls back -- cursor included -- so it is simply claimed again, finds the winner's run, and is
     * recorded as the ordinary "skip, already in queue". A slot that fails for any other reason is left
     * for the next tick rather than retried here, so one bad schedule cannot hold the loop.
     */
    public void addJobInQueue() {
        try {
            LocalDateTime now = BusinessTime.now();
            // The slots this pass has given up on; -1 always, because NOT IN () is not SQL.
            List<Long> passed = new ArrayList<>(Collections.singletonList(-1L));
            Set<Long> raced = new HashSet<>();
            int handled = 0;
            while (true) {
                Long[] slot = new Long[1];
                try {
                    Optional<Scheduler> claimed = this.transactions.execute(status -> {
                        Optional<Scheduler> next = this.transactionService.claimNextDueScheduler(now, passed);
                        next.ifPresent(scheduler -> {
                            slot[0] = scheduler.getSchedulerId();
                            this.enqueueSlot(scheduler);
                        });
                        return next;
                    });
                    if (claimed == null || !claimed.isPresent()) {
                        break;
                    }
                    handled++;
                } catch (RuntimeException ex) {
                    if (slot[0] == null) {
                        throw ex;
                    }
                    if (OneRunInFlight.isViolation(ex) && raced.add(slot[0])) {
                        logger.info("addJobInQueue --> scheduler {} lost its job to another enqueuer; claiming it "
                            + "again to record the slot as a skip.", slot[0]);
                        continue;
                    }
                    passed.add(slot[0]);
                    logger.error("Error in addJobInQueue for scheduler {}: {}.", slot[0], ExceptionUtil.getRootCauseMessage(ex));
                }
                Thread.sleep(50);
            }
            if (handled == 0) {
                logger.info("addJobInQueue --> NO scheduler is due at this timestamp");
            } else {
                logger.info("addJobInQueue --> {} due slot(s) enqueued or skipped.", handled);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            logger.error("Error in addJobInQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /** One claimed slot: a run, or a skip when the job already has one in flight; then the cursor moves on. */
    private void enqueueSlot(Scheduler scheduler) {
        JobQueue jobQueue;
        // Whether this pass moved the job's own running status. The skip branch does not, and the
        // announcement below reads that status to decide what to say -- see skipManualJobInQueue.
        boolean jobStatusMoved;
        if (this.bulkAction.getCountForInQueueJobByJobId(scheduler.getJobId()) > 0) {
            jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), BusinessTime.now(), JobStatus.Skip, "Job %s skip, already in queue.", true);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, already in queue.", scheduler.getJobId()));
            Optional<SourceJob> sourceJobForSkipMail = this.transactionService.findByJobId(scheduler.getJobId());
            if (sourceJobForSkipMail.isPresent() && sourceJobForSkipMail.get().isSkipJob()) {
                this.jobMail.send(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Skip);
            }
            jobStatusMoved = false;
        } else {
            // The run row BEFORE the job is marked Queue: should the index refuse it, nothing of this
            // attempt -- the job's status included -- survives the rollback.
            jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), BusinessTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
            this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.Queue);
            this.bulkAction.changeJobLastJobRun(scheduler.getJobId(), jobQueue.getStartTime());
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", scheduler.getJobId()));
            jobStatusMoved = true;
        }
        this.bulkAction.updateNextScheduler(scheduler);
        this.bulkAction.sendJobStatusNotification(scheduler.getJobId(), jobStatusMoved);
    }

    /**
     * The dispatch batch size, from orchestration_setting, defensively (MIG-136).
     *
     * This was `Long.valueOf(lookupData.getLookupValue())` on a lookup_data row, with no null check and
     * no parse guard, inside a method-wide catch: a value typed as "5,000", a trailing space, the row
     * renamed, or its "Store encrypted" box ticked stopped job dispatch platform-wide, and every job's
     * later slots became "skip, already in queue". The dial is Core's own now, in a table with no
     * encryption column; the reading stays defensive -- missing, null, unparseable or less than 1
     * dispatches DEFAULT_QUEUE_FETCH_LIMIT and says so, once per pass.
     */
    private long resolveQueueFetchLimit() {
        String value = this.transactionService.findOrchestrationSetting(ProcessUtil.QUEUE_FETCH_LIMIT);
        if (isNull(value)) {
            logger.warn("Setting {} is missing from orchestration_setting; dispatching {} rows this pass. Restore it.",
                ProcessUtil.QUEUE_FETCH_LIMIT, DispatchTiming.DEFAULT_QUEUE_FETCH_LIMIT);
            return DispatchTiming.DEFAULT_QUEUE_FETCH_LIMIT;
        }
        Long limit = ProcessUtil.parseLongOrNull(value);
        if (limit == null || limit < 1L) {
            logger.warn("Setting {} is not a positive whole number, so it cannot be used as a fetch limit; "
                + "dispatching {} rows this pass. Fix it in orchestration_setting.",
                ProcessUtil.QUEUE_FETCH_LIMIT, DispatchTiming.DEFAULT_QUEUE_FETCH_LIMIT);
            return DispatchTiming.DEFAULT_QUEUE_FETCH_LIMIT;
        }
        return limit;
    }

    /**
     * The dispatcher: hands each prepared run to its worker queue.
     *
     * It takes only runs the pre-dispatch phase has finished with (MIG-134), so there is no model call
     * and no broker call on this thread: a row is one local transaction -- the callback token's hash,
     * the job_send latch and a dispatch_outbox row -- and DispatchRelay publishes after commit (MIG-136).
     * The pass still stops starting rows at DispatchTiming.DISPATCH_BUDGET_MS, so that the last row it
     * starts ends inside the lock this runs under; the rest are picked up on the next run, in id order,
     * so nothing is skipped.
     */
    public void startJobInCurrentTimeSlot() {
        try {
            logger.info("runJobInCurrentTimeSlot --> FETCH JobQueue of current day STARTED ");
            List<JobQueue> jobQueues = this.transactionService.findAllJobForTodayWithLimit(this.resolveQueueFetchLimit(), BusinessTime.now());
            logger.info("runJobInCurrentTimeSlot --> FETCHED JobQueue of current day: size {} ", jobQueues.size());
            if (!jobQueues.isEmpty()) {
                long deadline = this.clock.getAsLong() + DispatchTiming.DISPATCH_BUDGET_MS;
                int dispatched = 0;
                for (JobQueue jobQueue : jobQueues) {
                    if (this.clock.getAsLong() > deadline) {
                        logger.warn("runJobInCurrentTimeSlot --> stopping at {} of {} to stay inside "
                            + "the scheduler lock; the rest are picked up on the next run.",
                            dispatched, jobQueues.size());
                        break;
                    }
                    // Everything logged about this run's dispatch carries its correlation id, which the
                    // token write stamps on the row: a callback that echoes no id is logged under it too
                    // (MIG-95). A retry keeps the id of its first dispatch.
                    if (jobQueue.getCorrelationId() == null) {
                        jobQueue.setCorrelationId(CorrelationId.generate());
                    }
                    CorrelationId.set(jobQueue.getCorrelationId());
                    try {
                        this.pause.accept(DispatchTiming.PER_ROW_PAUSE_MS);
                        Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
                        if (sourceJob.isPresent()) {
                            this.pushMessageToQueue(sourceJob.get(), jobQueue);
                        } else {
                            this.changeStatusForLastJob(jobQueue, String.format(
                                "Job %s failed in the queue because the main job is deleted or inactive.", jobQueue.getJobId()));
                        }
                        dispatched++;
                    } catch (Exception ex) {
                        logger.error("Error in runJobInCurrentTimeSlot: {}.", ExceptionUtil.getRootCauseMessage(ex));
                    } finally {
                        CorrelationId.clear();
                    }
                }
                return;
            }
            logger.info("runJobInCurrentTimeSlot --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error in runJobInCurrentTimeSlot: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * Dispatches one prepared run: the message, with the run's token in it, written to dispatch_outbox in
     * the same local transaction as the token's hash and the job_send latch.
     *
     * The route is asked again (rows 1 to 4): the task may have been reconfigured since the run was
     * prepared, and a run that can no longer go anywhere is closed, not left. Anything thrown -- the
     * token or the outbox row could not be written -- rolls the three back together and is the one
     * retryable failure here (row 6); nothing was published, because nothing is published before commit.
     */
    private void pushMessageToQueue(SourceJob sourceJob, JobQueue jobQueue) throws Exception {
        DispatchRoute route = DispatchRoute.of(sourceJob, jobQueue.getJobId());
        if (route.refused()) {
            this.changeStatusForLastJob(jobQueue, route.refusal);
            return;
        }
        if (isNull(jobQueue.getDispatchPayload())) {
            // Not prepared: not this pass's to send. The pick-up query never returns such a row; this
            // is the belt to its braces, and leaves the run for the pre-dispatch phase.
            logger.warn("Run {} reached the dispatcher unprepared; left for the pre-dispatch phase.", jobQueue.getJobQueueId());
            return;
        }
        try {
            this.transactions.execute(status -> {
                String payload = this.getSourceJobDetail(sourceJob, jobQueue, jobQueue.getDispatchPayload());
                jobQueue.setJobSend(true);
                this.transactionService.updateJobQueue(jobQueue);
                this.dispatchOutbox.write(new DispatchOutbox.Record(jobQueue.getJobQueueId(), Math.max(1, jobQueue.getAttempt()),
                    sourceJob.getTenantId(), route.taskType.getSourceTaskTypeId(), route.topic, route.partition,
                    UUID.randomUUID().toString(), payload, this.headersFor(sourceJob, jobQueue)));
                return null;
            });
            logger.info("Run {} of job {} written to the dispatch outbox for {}.", jobQueue.getJobQueueId(),
                jobQueue.getJobId(), route.topic);
        } catch (Exception ex) {
            jobQueue.setJobSend(false);
            // Retryable: building the message or writing it down failed, which is the transient case.
            this.changeStatusForLastJob(jobQueue, String.format(
                "Job %s could not be dispatched: %s", jobQueue.getJobId(), DispatchFailures.reasonFor(ex)), true);
            logger.error("Error in pushMessageToQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * The record headers every dispatch carries (MIG-26): the tenant and the user the run belongs to,
     * the names service-1/2/3's TaskHeaders already parse, and the correlation id (MIG-95). A job with
     * no tenant or owner simply has no such header -- the workers' platform-branch fallback for
     * genuinely tenant-less executions stays theirs.
     */
    private Map<String, String> headersFor(SourceJob sourceJob, JobQueue jobQueue) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (sourceJob.getTenantId() != null) {
            headers.put("x-tenant-id", String.valueOf(sourceJob.getTenantId()));
        }
        if (sourceJob.getAssignedUserId() != null) {
            headers.put("x-user-id", String.valueOf(sourceJob.getAssignedUserId()));
        }
        if (jobQueue.getCorrelationId() != null) {
            headers.put(CorrelationId.HEADER, jobQueue.getCorrelationId());
        }
        return headers;
    }

    /** DispatchRelay: the broker took the run's message. Only a run still waiting on this hand-off moves. */
    @Override
    public void published(long jobQueueId, int attempt, long offset) {
        this.transactions.execute(status -> {
            Optional<JobQueue> run = this.awaitingHandOff(jobQueueId, attempt);
            if (run.isPresent()) {
                Optional<SourceJob> sourceJob = this.transactionService.findByJobId(run.get().getJobId());
                this.handleSendSuccess(offset, sourceJob.orElse(null), run.get());
            }
            return null;
        });
    }

    /** DispatchRelay: the broker would not take it. The same transient failure the in-pass send reported. */
    @Override
    public void publishFailed(long jobQueueId, int attempt, Throwable cause) {
        this.transactions.execute(status -> {
            Optional<JobQueue> run = this.awaitingHandOff(jobQueueId, attempt);
            if (run.isPresent()) {
                Optional<SourceJob> sourceJob = this.transactionService.findByJobId(run.get().getJobId());
                this.handleSendFailure(cause, null, sourceJob.orElse(null), run.get());
            }
            return null;
        });
    }

    /**
     * The run, if it is still the hand-off the relay is reporting on: queued, latched as sent, same
     * attempt. A run an operator failed meanwhile, or one already moved on, is left as it is.
     */
    private Optional<JobQueue> awaitingHandOff(long jobQueueId, int attempt) {
        Optional<JobQueue> run = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        if (run.isPresent() && run.get().getJobStatus() == JobStatus.Queue && run.get().isJobSend()
            && Math.max(1, run.get().getAttempt()) == attempt) {
            return run;
        }
        logger.warn("Hand-off of run {} attempt {} reported after the run moved on; left as it is.", jobQueueId, attempt);
        return Optional.empty();
    }

    private void handleSendSuccess(long offset, SourceJob sourceJob, JobQueue jobQueue) {
        logger.info("Run {} handed to the worker queue at offset [{}]", jobQueue.getJobQueueId(), offset);

        jobQueue.setJobSend(true);
        jobQueue.setJobStatus(JobStatus.Start);
        // The payload is NOT in here. jobStatusMessage is what the Recent runs list and the job
        // row put in front of a person, and a run's whole task XML -- four hundred characters of
        // escaped markup -- pushed everything worth reading off the end of the line.
        jobQueue.setJobStatusMessage("Handed to the worker queue at offset " + offset + ".");
        this.transactionService.updateJobQueue(jobQueue);
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Start);

        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format(
            "Job %s handed to the worker queue at offset %s.", jobQueue.getJobId(), offset));
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
    }

    private void handleSendFailure(Throwable ex, String payload, SourceJob sourceJob, JobQueue jobQueue) {
        logger.error("Unable to hand run {} to the worker queue due to: {}", jobQueue.getJobQueueId(), ex.getMessage());

        jobQueue.setJobSend(false);
        // Reason first and payload nowhere: the only part anyone needs -- why it failed -- used to sit
        // past the end of a two-line clamp behind four hundred characters of escaped task XML.
        String reason = DispatchFailures.reasonFor(ex);
        jobQueue.setJobStatusMessage("Could not hand this run to the worker queue: " + reason);

        this.changeStatusForLastJob(jobQueue, String.format(
            "Job %s could not be handed to the worker queue: %s", jobQueue.getJobId(), reason), true);
    }

    /** See DispatchFailures.close: every dispatch-side phase closes a run it will not send the same way. */
    private void changeStatusForLastJob(JobQueue jobQueue, String statusMessage) {
        this.changeStatusForLastJob(jobQueue, statusMessage, false);
    }

    private void changeStatusForLastJob(JobQueue jobQueue, String statusMessage, boolean retryable) {
        this.failures.close(jobQueue, statusMessage, retryable);
    }

    private String getSourceJobDetail(SourceJob sourceJob, JobQueue jobQueue, String taskPayload) {
        JobPayloadDTO dto = new JobPayloadDTO();
        dto.setJobQueueId(jobQueue.getJobQueueId());
        dto.setJobId(jobQueue.getJobId());
        // The run's own proof for its callbacks, minted and saved here -- in the same transaction as the
        // outbox row that carries it, so the server knows the token before any worker can echo it.
        dto.setCallbackToken(this.runCallbackTokens.issue(jobQueue));
        dto.setAttempt(Math.max(1, jobQueue.getAttempt()));
        dto.setCorrelationId(jobQueue.getCorrelationId());
        if (!ProcessUtil.isNull(sourceJob.getTaskDetail())) {
            Long homePageId = sourceJob.getTaskDetail().getHomePageId();
            if (homePageId != null) {
                dto.setHomePageId(this.transactionService.findHomePageUrl(homePageId));
            }
            // pipelineId is no longer a PIPELINE_IDS lookup row id -- Task Forms now define a
            // pipeline directly by its own id string, so it goes straight through.
            String pipelineId = sourceJob.getTaskDetail().getPipelineId();
            if (!ProcessUtil.isNull(pipelineId)) {
                dto.setPipelineId(pipelineId.trim());
            }
            // The document the pre-dispatch phase prepared, with any AI step's answers in it -- not
            // the task's stored one.
            dto.setTaskPayload(taskPayload);
        }
        dto.setPriority(sourceJob.getPriority());
        return dto.toString();
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}