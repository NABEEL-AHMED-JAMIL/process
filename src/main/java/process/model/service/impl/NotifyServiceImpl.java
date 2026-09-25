package process.model.service.impl;

import process.util.BusinessTime;
import org.barco.platform.meter.Meter;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import org.barco.notifications.contract.JobLogAppended;
import process.callback.CallbackKeys;
import process.callback.CallbackReceipts;
import process.callback.ReplayedResponse;
import process.callback.TransitionConflict;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.NotifyService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static process.util.ProcessUtil.ERROR;

/**
 * @author Nabeel Ahmed
 * */
@Service
@Transactional
public class NotifyServiceImpl implements NotifyService {

    /** The meter, when the console has one; optional so hand-built instances in tests need none. */
    @Autowired(required = false)
    private MeterReporter meter;


    private Logger logger = LoggerFactory.getLogger(NotifyServiceImpl.class);

    private final BulkAction bulkAction;
    private final JobMail jobMail;
    private final TransactionServiceImpl transactionService;
    private final NotificationPort notifications;

    private final CallbackReceipts receipts;

    public NotifyServiceImpl(
        BulkAction bulkAction,
        JobMail jobMail,
        TransactionServiceImpl transactionService,
        NotificationPort notifications) {
        this(bulkAction, jobMail, transactionService, notifications, CallbackReceipts.NONE);
    }

    @Autowired
    public NotifyServiceImpl(
        BulkAction bulkAction,
        JobMail jobMail,
        TransactionServiceImpl transactionService,
        NotificationPort notifications,
        CallbackReceipts receipts) {
        this.bulkAction = bulkAction;
        this.jobMail = jobMail;
        this.transactionService = transactionService;
        this.notifications = notifications;
        this.receipts = receipts;
    }

    /**
     * The live worker's state change, applied once per idempotency key (MIG-18).
     *
     * The key is claimed before anything is read or written, in this same transaction, so a
     * redelivery -- sequential or racing -- finds the claim and is handed the first delivery's answer
     * instead of repeating its writes: one audit line, one status change, one email. A refused
     * transition is an answer too, and a redelivery of it is refused the same way. Running with no
     * key from the worker claims nothing: that is the heartbeat, and every one is new.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResponseDto changeState(SourceJobQueueDto jobQueue, String idempotencyKey) {
        String key = idempotencyKey != null ? idempotencyKey
            : this.derivedKey(jobQueue.getJobQueueId(), jobQueue.getJobStatus());
        String request = CallbackKeys.changeState(jobQueue.getJobStatus());
        Optional<ResponseDto> replayed = this.claim(jobQueue.getJobQueueId(), key, request, jobQueue);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        return this.recorded(jobQueue.getJobQueueId(), key, this.changeState(jobQueue));
    }

    public ResponseDto addLogs(SourceJobQueueDto jobQueue, String idempotencyKey) {
        Optional<ResponseDto> replayed = this.claim(jobQueue.getJobQueueId(), idempotencyKey, CallbackKeys.ADD_LOGS, jobQueue);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        return this.recorded(jobQueue.getJobQueueId(), idempotencyKey, this.addLogs(jobQueue));
    }

    public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages, String idempotencyKey) {
        Optional<ResponseDto> replayed = this.claim(jobQueueId, idempotencyKey, CallbackKeys.ADD_LOGS_BATCH, messages.size());
        if (replayed.isPresent()) {
            return replayed.get();
        }
        return this.recorded(jobQueueId, idempotencyKey, this.addLogsBatch(jobId, jobQueueId, messages));
    }

    public void noteRefusedCallback(Long jobQueueId, JobStatus reportedStatus) {
        if (jobQueueId == null) {
            return;
        }
        String reported = reportedStatus == null ? "log" : reportedStatus.name();
        int noted = this.transactionService.noteRefusedCallback(jobQueueId, BusinessTime.now(), reported);
        if (noted > 0) {
            logger.warn("Run {}: its worker's report ({}) was refused for an expired callback token; the run "
                + "will be closed as interrupted by the next stall sweep.", jobQueueId, reported);
        }
    }

    public Optional<ResponseDto> replay(Long jobQueueId, JobStatus jobStatus, String request, String idempotencyKey) {
        String key = idempotencyKey != null ? idempotencyKey
            : jobStatus == null ? null : this.derivedKey(jobQueueId, jobStatus);
        if (key == null || jobQueueId == null) {
            return Optional.empty();
        }
        return this.receipts.find(jobQueueId, key)
            .filter(receipt -> request.equals(receipt.request))
            .map(receipt -> (ResponseDto) new ReplayedResponse(receipt, null));
    }

    /**
     * A terminal state change the worker sent no key for is keyed on its run and the attempt its
     * token was minted for -- not the row's attempt, which a retry has already moved on. A run
     * dispatched before tokens existed has no token attempt, and its own attempt stands in.
     */
    private String derivedKey(Long jobQueueId, JobStatus status) {
        if (jobQueueId == null) {
            return null;
        }
        return this.transactionService.findJobQueueByJobQueueId(jobQueueId)
            .map(run -> CallbackKeys.derived(status, run.getCallbackTokenAttempt() != null
                ? run.getCallbackTokenAttempt() : run.getAttempt()))
            .orElse(null);
    }

    /** Empty when this delivery is the first with its key (or has none); the first answer otherwise. */
    private Optional<ResponseDto> claim(Long jobQueueId, String key, String request, Object data) {
        if (key == null || jobQueueId == null) {
            return Optional.empty();
        }
        Optional<CallbackReceipts.Receipt> prior = this.receipts.claim(jobQueueId, key, request, BusinessTime.now());
        if (!prior.isPresent()) {
            return Optional.empty();
        }
        if (!request.equals(prior.get().request)) {
            logger.warn("Run {}: Idempotency-Key {} was first used for {} and is now sent with {}; refused.",
                jobQueueId, key, prior.get().request, request);
            return Optional.of(new ResponseDto(ERROR, String.format(
                "Idempotency-Key %s was already used for a different callback on this run.", key), data));
        }
        logger.info("Run {}: {} with Idempotency-Key {} was already applied; answering it again.", jobQueueId, request, key);
        return Optional.of(new ReplayedResponse(prior.get(), data));
    }

    private ResponseDto recorded(Long jobQueueId, String key, ResponseDto outcome) {
        if (key != null && jobQueueId != null) {
            this.receipts.record(jobQueueId, key, outcome.getStatus(), outcome.getMessage());
        }
        return outcome;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ResponseDto changeState(SourceJobQueueDto jobQueue) {
        logger.info("Received request to change job {} queue {} status to {}", jobQueue.getJobId(), jobQueue.getJobQueueId(), jobQueue.getJobStatus());
        Optional<SourceJob> live = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
        // Owner rule "keep the bill" (2026-09-24): a run whose job was deleted or switched off while it worked still
        // reports -- the run row and its token are the proof, not the job's current status -- so its outcome lands
        // and its usage is metered. Deletion only stops future work: such a run is not retried (below).
        Optional<SourceJob> job = live.isPresent() ? live : this.transactionService.findByJobId(jobQueue.getJobId());
        boolean jobLive = live.isPresent();
        if (!job.isPresent()) {
            logger.warn("Job {} not found", jobQueue.getJobId());
            return new ResponseDto(ERROR, String.format("Job with id %s not found or not active", jobQueue.getJobId()), jobQueue);
        }
        Optional<JobQueue> run = this.runOfJob(jobQueue.getJobId(), jobQueue.getJobQueueId());
        if (!run.isPresent()) {
            logger.warn("Queue {} does not belong to job {}", jobQueue.getJobQueueId(), jobQueue.getJobId());
            return new ResponseDto(ERROR, String.format("Queue with id %s does not belong to job %s",
                jobQueue.getJobQueueId(), jobQueue.getJobId()), jobQueue);
        }
        if (!jobLive) {
            logger.info("Run {} of job {} reports {} after its job was {}; recording it and its usage (keep the bill).",
                jobQueue.getJobQueueId(), jobQueue.getJobId(), jobQueue.getJobStatus(), job.get().getJobStatus());
        }
        // The RUN's own status decides (MIG-201), not the job's running status: that one is the job's summary of
        // its latest run, written by other paths too, and a job whose running status was null threw here.
        JobStatus currentStatus = run.get().getJobStatus();
        JobStatus newStatus = jobQueue.getJobStatus();
        // The hand-off race (run 7309): the relay publishes, and the run and the job only move to Start
        // in the transaction after the broker's ack. A worker quicker than that ack reports while the run
        // still says Queue. A run latched as sent IS handed off from any worker's side -- its message and
        // the token proving this callback exist only in what was published -- so for that run, and only
        // that run, the callback is read against Start: Running, or a decline (Failed).
        if (currentStatus == JobStatus.Queue && run.get().isJobSend()) {
            logger.info("Run {} reported {} before its hand-off was recorded; taking it as handed off.",
                jobQueue.getJobQueueId(), newStatus);
            currentStatus = JobStatus.Start;
        }
        logger.info("Run {} of job {} current status: {}, requested status: {}", jobQueue.getJobQueueId(),
            jobQueue.getJobId(), currentStatus, newStatus);
        if (!this.isValidStatusTransition(currentStatus, newStatus)) {
            logger.warn("Invalid status transition for run {} of job {} from {} to {}", jobQueue.getJobQueueId(),
                jobQueue.getJobId(), currentStatus, newStatus);
            return TransitionConflict.refusal(currentStatus, newStatus, jobQueue);
        }
        // A worker DECLINING a run it will not start -- a pipeline it does not have, a record on the wrong topic
        // (MIG-201): Failed straight from Start. Closed now rather than left for the stall sweep, but never
        // retried (another attempt meets the same configuration, as an unrouted run does, MIG-45) and never
        // metered (no work was done).
        boolean declined = newStatus == JobStatus.Failed && currentStatus == JobStatus.Start;
        if (declined) {
            logger.info("Run {} of job {} was declined by its worker before it started: {}; not retried.",
                jobQueue.getJobQueueId(), jobQueue.getJobId(), jobQueue.getJobStatusMessage());
        }
        // The live worker callback, and so the retry decision's real home. A task that reports it
        // could not finish -- a source briefly unreachable, an object store that refused one
        // connection -- is the ordinary way an ETL run fails and the one a second attempt most
        // often clears.
        //
        // Offered before any of the writes below, because those ARE the failure as far as the rest
        // of the platform is concerned: the job's status, the run's status, the end time, the
        // socket announcement and the fail mail. Making them first and retrying afterwards would
        // tell everyone the run had failed moments before trying it again -- and on a job with
        // three attempts, would send three failure emails for one eventual failure.
        //
        // scheduleRetry writes the worker's own explanation into the audit log, so returning early
        // loses nothing it reported.
        if (newStatus == JobStatus.Failed && jobLive && !declined
            && this.bulkAction.scheduleRetry(jobQueue.getJobQueueId(), jobQueue.getJobId(),
                jobQueue.getJobStatusMessage())) {
            logger.info("Job {} run {} failed and has been queued for another attempt.",
                jobQueue.getJobId(), jobQueue.getJobQueueId());
            return new ResponseDto(String.format(
                "Job %s run failed and has been queued for another attempt.", jobQueue.getJobId()), jobQueue);
        }
        logger.info("Updating status for job {} to {}", jobQueue.getJobId(), newStatus);
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), newStatus);
        JobStatus runBefore = this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), newStatus, jobQueue.getJobStatusMessage());
        // The pipeline execution SLI (MIG-196): the worker's own outcome, or its decline if it never reported Running.
        this.bulkAction.runEnded(jobQueue.getJobQueueId(), runBefore, newStatus, RunEnd.reportedBy(runBefore, newStatus));
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), jobQueue.getJobStatusMessage());
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId(), jobQueue.getJobQueueId(), currentStatus != newStatus);
        if (newStatus == JobStatus.Failed || newStatus == JobStatus.Completed) {
            logger.info("Setting end date for job {}", jobQueue.getJobId());
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), jobQueue.getEndTime());
            // One run, once: Completed and Failed both did the work (a decline did none); the run id is the key.
            if (this.meter != null && job.get().getTenantId() != null && !declined) {
                this.meter.report(UsageEvent.of(job.get().getTenantId(), Meter.PIPELINE_RUNS, 1, "run#" + jobQueue.getJobQueueId())
                    .subject("job", String.valueOf(jobQueue.getJobId())).run(jobQueue.getJobQueueId()).source("console")
                    .note(newStatus.name()));
            }
        }
        switch (newStatus) {
            case Failed:
                logger.info("Job {} marked as Failed", jobQueue.getJobId());
                if (job.get().isFailJob()) {
                    logger.info("Sending failure notification email for job {}", jobQueue.getJobId());
                    this.jobMail.send(jobQueue, newStatus);
                }
                break;
            case Completed:
                logger.info("Job {} marked as Completed", jobQueue.getJobId());
                if (job.get().isCompleteJob()) {
                    logger.info("Sending completion notification email for job {}", jobQueue.getJobId());
                    this.jobMail.send(jobQueue, newStatus);
                }
                break;
            default:
                break;
        }
        logger.info("Successfully changed job {} status to {}", jobQueue.getJobId(), newStatus);
        return new ResponseDto(String.format("Job %s status changed to %s", jobQueue.getJobId(), newStatus), jobQueue);
    }

    public ResponseDto addLogs(SourceJobQueueDto jobQueue) {
        logger.info("Received request to add logs for job {} queue {}", jobQueue.getJobId(), jobQueue.getJobQueueId());
        Optional<SourceJob> job = this.jobOfRun(jobQueue.getJobId());
        if (!job.isPresent()) {
            logger.warn("Job {} not found or not active", jobQueue.getJobId());
            return new ResponseDto(ERROR, String.format("Job with id %s not found or not active", jobQueue.getJobId()), jobQueue);
        }
        if (!this.queueBelongsToJob(jobQueue.getJobId(), jobQueue.getJobQueueId())) {
            logger.warn("Queue {} does not belong to job {}", jobQueue.getJobQueueId(), jobQueue.getJobId());
            return new ResponseDto(ERROR, String.format("Queue with id %s does not belong to job %s",
                jobQueue.getJobQueueId(), jobQueue.getJobId()), jobQueue);
        }
        logger.info("Adding logs for job {} queue {}", jobQueue.getJobId(), jobQueue.getJobQueueId());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), jobQueue.getJobStatusMessage());
        // The same pipeline callback that writes the line announces it, so an open run-logs
        // screen appends it instead of polling every five seconds for one that may not come.
        this.notifications.jobLogAppended(job.get().getTenantId(), new JobLogAppended().setJobId(jobQueue.getJobId())
            .setJobQueueId(jobQueue.getJobQueueId()).setLineSeq(lineSeq(0)).setMessage(jobQueue.getJobStatusMessage()));
        logger.info("Successfully added logs for job {} queue {}", jobQueue.getJobId(), jobQueue.getJobQueueId());
        return new ResponseDto(String.format("Logs added for job %s queue %s", jobQueue.getJobId(), jobQueue.getJobQueueId()), jobQueue);
    }

    /**
     * Many log lines in one call.
     *
     * The per-line endpoint repeated an identical job lookup for every line -- fifty lookups
     * and fifty round trips for a run that produced fifty lines. This resolves the job once
     * and writes the lot. Each line is still announced individually, because the run-logs
     * screen appends lines and would otherwise have to learn a second message shape.
     */
    public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages) {
        Optional<SourceJob> job = this.jobOfRun(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, String.format("Job with id %s not found or not active", jobId));
        }
        if (!this.queueBelongsToJob(jobId, jobQueueId)) {
            return new ResponseDto(ERROR, String.format("Queue with id %s does not belong to job %s", jobQueueId, jobId));
        }
        this.bulkAction.saveJobAuditLogs(jobQueueId, messages);
        for (int i = 0; i < messages.size(); i++) {
            this.notifications.jobLogAppended(job.get().getTenantId(), new JobLogAppended().setJobId(jobId)
                .setJobQueueId(jobQueueId).setLineSeq(lineSeq(i)).setMessage(messages.get(i)));
        }
        return new ResponseDto(
            String.format("%s log line(s) added for job %s queue %s", messages.size(), jobId, jobQueueId),
            messages.size());
    }

    /**
     * The job a callback's run belongs to: the Active job, or -- keep the bill -- the same job deleted or switched
     * off while its run was working. queueBelongsToJob still decides that the run is that job's.
     */
    private Optional<SourceJob> jobOfRun(Long jobId) {
        Optional<SourceJob> live = this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active);
        return live.isPresent() ? live : this.transactionService.findByJobId(jobId);
    }

    /**
     * A callback names the job and the run separately in its path, and until now nothing tied
     * the two together -- a caller holding the worker token could quote one job it is entitled
     * to and any other tenant's queue id, and the write landed on the latter. The queue row is
     * the only thing that knows which job it belongs to, so it is what decides.
     */
    private boolean queueBelongsToJob(Long jobId, Long jobQueueId) {
        return this.runOfJob(jobId, jobQueueId).isPresent();
    }

    /** The run, when it exists and is this job's; empty otherwise. */
    private Optional<JobQueue> runOfJob(Long jobId, Long jobQueueId) {
        if (jobId == null || jobQueueId == null) {
            return Optional.empty();
        }
        return this.transactionService.findJobQueueByJobQueueId(jobQueueId)
            .filter(run -> jobId.equals(run.getJobId()));
    }

    /**
     * The run state machine a worker callback is held to (C7, MIG-139), read off the run row since MIG-201.
     *
     * Before MIG-201, six edges left the in-flight states -- Queue->Start, Start->Start, Start->Running,
     * Running->Running, Running->Failed, Running->Completed -- plus the self-loops Failed->Failed and
     * Completed->Completed: eight accepted cells. MIG-201 adds ONE edge, Start->Failed, deliberately: a worker
     * DECLINING a run it will not start (a pipelineId it does not have, a record on the wrong topic), which
     * otherwise sat in Start until the stall sweep. Seven edges, nine cells (JobStatusTransitionTableTest).
     * A decline is a failure of the run, not a refusal: the run ends Failed, not retried and not metered.
     * Start->Completed stays illegal, and the stall sweep is untouched: it still writes Interrupt, never Failed (C4).
     * No status at all is a refusal, never a NullPointerException.
     */
    private boolean isValidStatusTransition(JobStatus currentStatus, JobStatus newStatus) {
        if (currentStatus == null) {
            return false;
        }
        switch (currentStatus) {
            case Queue:

                return newStatus == JobStatus.Start;

            case Start:
                return newStatus == JobStatus.Start
                        || newStatus == JobStatus.Running
                        || newStatus == JobStatus.Failed;

            case Running:
                return newStatus == JobStatus.Running
                        || newStatus == JobStatus.Failed
                        || newStatus == JobStatus.Completed;

            case Failed:
            case Completed:
                return currentStatus == newStatus;

            default:
                return false;
        }
    }


    /**
     * A log line's position in its run, which the contract dedupes a redelivered line on. Audit lines
     * are keyed by random UUIDs in OpenSearch, so there is no stored sequence to use: this is the
     * receipt time in microseconds plus the line's index in its batch -- unique within the run and
     * increasing with time.
     */
    private static long lineSeq(int indexInBatch) {
        return System.currentTimeMillis() * 1000L + indexInBatch;
    }
}
