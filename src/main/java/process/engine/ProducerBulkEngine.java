package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.emailer.EmailMessagesFactory;
import process.engine.dto.JobPayloadDTO;
import process.security.RunCallbackTokens;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.service.impl.TransactionServiceImpl;
import process.util.KafkaTopicPartitionUtil;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.*;
import static java.util.Objects.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class ProducerBulkEngine {

    /**
     * How long one dispatch pass may take. The @SchedulerLock around it is ten minutes, and the
     * work has to finish inside that or a second instance can claim the same rows. Seven leaves
     * room for a slow broker without letting the pass outlive its lock.
     */
    private static final long DISPATCH_BUDGET_MS = 7 * 60 * 1000L;

    public Logger logger = LogManager.getLogger(ProducerBulkEngine.class);

    private final RunCallbackTokens runCallbackTokens;
    private final BulkAction bulkAction;
    private final TransactionServiceImpl transactionService;
    private final EmailMessagesFactory emailMessagesFactory;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;

    public ProducerBulkEngine(BulkAction bulkAction,
        TransactionServiceImpl transactionService,
        EmailMessagesFactory emailMessagesFactory,
        KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver,
        RunCallbackTokens runCallbackTokens) {
        this.runCallbackTokens = runCallbackTokens;
        this.bulkAction = bulkAction;
        this.transactionService = transactionService;
        this.emailMessagesFactory = emailMessagesFactory;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
    }

    public void addManualJobInQueue(SourceJob sourceJob) {
        this.bulkAction.changeJobStatus(sourceJob.getJobId(), JobStatus.Queue);
        JobQueue jobQueue = this.bulkAction.createJobQueueV1(sourceJob.getJobId(),
            LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
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
            this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Skip);
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
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STALLED_AFTER_MINUTES);
            List<JobQueue> stalled = this.transactionService.findStalledRuns(cutoff);
            if (stalled.isEmpty()) {
                return;
            }
            logger.warn("reconcileStalledRuns --> {} run(s) have been in flight since before {}; "
                + "closing them so their jobs can be scheduled again.", stalled.size(), cutoff);
            for (JobQueue jobQueue : stalled) {
                try {
                    jobQueue.setJobStatus(JobStatus.Interrupt);
                    jobQueue.setEndTime(LocalDateTime.now());
                    jobQueue.setJobStatusMessage(String.format(
                        "Job %s stopped reporting and was closed after %d hours. Its worker may "
                        + "have finished the work -- check the output before running it again.",
                        jobQueue.getJobId(), STALLED_AFTER_MINUTES / 60));
                    this.transactionService.saveJobQueue(jobQueue);
                    // Quote the time it actually has. A run that was never dispatched has no
                    // start_time, and "no update since null" reads as "we lost track of it" when
                    // what happened is "it was never picked up" -- two different incidents, told
                    // apart here or nowhere.
                    this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), jobQueue.getStartTime() != null
                        ? String.format("Run closed automatically: no update from the worker since %s.",
                            jobQueue.getStartTime())
                        : String.format("Run closed automatically: queued at %s and never picked up.",
                            jobQueue.getDateCreated()));
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

    public void addJobInQueue() {
        try {
            logger.info("addJobInQueue --> FETCH due schedulers STARTED ");
            LocalDateTime now = LocalDateTime.now();
            List<Scheduler> dueSchedulers = this.transactionService.findDueSchedulers(now);
            logger.info("addJobInQueue --> FETCHED due schedulers: size {} ", dueSchedulers.size());
            if (!dueSchedulers.isEmpty()) {

                dueSchedulers.stream()
                    .forEach(scheduler -> {
                        try {
                            Thread.sleep(50);
                            JobQueue jobQueue;
                            // Whether this pass moved the job's own running status. The skip
                            // branch does not, and the announcement below reads that status to
                            // decide what to say -- see skipManualJobInQueue for the full story.
                            boolean jobStatusMoved;
                            if (this.bulkAction.getCountForInQueueJobByJobId(scheduler.getJobId()) > 0) {

                                jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(), JobStatus.Skip, "Job %s skip, already in queue.", true);
                                this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, already in queue.", scheduler.getJobId()));
                                Optional<SourceJob> sourceJobForSkipMail = this.transactionService.findByJobId(scheduler.getJobId());
                                if (sourceJobForSkipMail.isPresent() && sourceJobForSkipMail.get().isSkipJob()) {
                                    this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Skip);
                                }
                                jobStatusMoved = false;
                            } else {
                                this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.Queue);
                                jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
                                this.bulkAction.changeJobLastJobRun(scheduler.getJobId(), jobQueue.getStartTime());
                                this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", scheduler.getJobId()));
                                jobStatusMoved = true;
                            }

                            this.bulkAction.updateNextScheduler(scheduler);
                            this.bulkAction.sendJobStatusNotification(scheduler.getJobId(), jobStatusMoved);
                        } catch (Exception ex) {
                            logger.error("Error in addJobInQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
                        }
                });
                return;
            }
            logger.info("addJobInQueue --> NO scheduler is due at this timestamp");
        } catch (Exception ex) {
            logger.error("Error in addJobInQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * How many queued rows one dispatch pass takes when QUEUE_FETCH_LIMIT cannot be read.
     *
     * The same order of magnitude as the value that ships, so a fallback pass behaves like a
     * normal one rather than either stalling the queue or trying to drain it in a single tick.
     */
    private static final long DEFAULT_QUEUE_FETCH_LIMIT = 1000L;

    /**
     * The dispatch batch size, from the lookup, defensively.
     *
     * This was `Long.valueOf(lookupData.getLookupValue())` with no null check and no parse guard,
     * inside a method-wide catch. Anything that made it throw stopped job dispatch platform-wide
     * and said so in exactly one server-log line: a value typed as "5,000", a trailing space, the
     * lookup renamed, or -- the easy one -- the row's "Store encrypted" box ticked, which stores
     * ciphertext that nothing here decrypts and then serves the value back masked. With dispatch
     * dead, every job_queue row stayed in Queue, and because the dispatcher counts Queue rows when
     * deciding whether a job is busy, every later slot for every job was recorded as "skip,
     * already in queue". Nothing on screen connected any of that to a lookup value.
     *
     * A misconfigured dial should not be able to stop the platform: say so loudly, once per pass,
     * and carry on at a sane rate.
     */
    private long resolveQueueFetchLimit() {
        LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT);
        if (isNull(lookupData) || isNull(lookupData.getLookupValue())) {
            logger.warn("Lookup {} is missing; dispatching {} rows this pass. Restore the setting.",
                ProcessUtil.QUEUE_FETCH_LIMIT, DEFAULT_QUEUE_FETCH_LIMIT);
            return DEFAULT_QUEUE_FETCH_LIMIT;
        }
        Long limit = ProcessUtil.parseLongOrNull(lookupData.getLookupValue());
        if (limit == null || limit < 1L) {
            logger.warn("Lookup {} is not a positive whole number, so it cannot be used as a fetch "
                + "limit; dispatching {} rows this pass. Fix the setting on /settings/lookup.",
                ProcessUtil.QUEUE_FETCH_LIMIT, DEFAULT_QUEUE_FETCH_LIMIT);
            return DEFAULT_QUEUE_FETCH_LIMIT;
        }
        return limit;
    }

    public void startJobInCurrentTimeSlot() {
        try {
            logger.info("runJobInCurrentTimeSlot --> FETCH JobQueue of current day STARTED ");
            List<JobQueue> jobQueues = this.transactionService.findAllJobForTodayWithLimit(this.resolveQueueFetchLimit(), LocalDateTime.now());
            logger.info("runJobInCurrentTimeSlot --> FETCHED JobQueue of current day: size {} ", jobQueues.size());
            if (!jobQueues.isEmpty()) {
                // The lock this runs under lasts ten minutes, and the loop sleeps 100ms per job:
                // a full fetch of 5,000 takes 8m20s, which leaves less than two minutes of room.
                // Anything that slows a batch down -- a slow broker, a slow database -- takes it
                // past the lock, at which point another instance may pick up the same rows and
                // dispatch them twice. Stop before the deadline instead and leave the rest: this
                // runs again in a minute, and the queue is ordered, so nothing is skipped.
                long deadline = System.currentTimeMillis() + DISPATCH_BUDGET_MS;
                int dispatched = 0;
                for (JobQueue jobQueue : jobQueues) {
                    if (System.currentTimeMillis() > deadline) {
                        logger.warn("runJobInCurrentTimeSlot --> stopping at {} of {} to stay inside "
                            + "the scheduler lock; the rest are picked up on the next run.",
                            dispatched, jobQueues.size());
                        break;
                    }
                    Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
                    try {
                        Thread.sleep(100);
                        if (sourceJob.isPresent()) {
                            this.pushMessageToQueue(sourceJob.get(), jobQueue);
                        } else {
                            this.changeStatusForLastJob(jobQueue, String.format(
                                "Job %s failed in the queue because the main job is deleted or inactive.", jobQueue.getJobId()));
                        }
                        dispatched++;
                    } catch (Exception ex) {
                        logger.error("Error in runJobInCurrentTimeSlot: {}.", ExceptionUtil.getRootCauseMessage(ex));
                    }
                }
                return;
            }
            logger.info("runJobInCurrentTimeSlot --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error in runJobInCurrentTimeSlot: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    private void pushMessageToQueue(SourceJob sourceJob, JobQueue jobQueue) throws Exception {
        SourceTask sourceTask = sourceJob.getTaskDetail();
        /*
         * A run that cannot be dispatched has to be closed, not abandoned.
         *
         * The whole body below used to sit inside the task-type check with no else, and the task
         * itself was dereferenced unguarded -- while SourceJobServiceImpl guards both of exactly
         * these fields before reading them. So a queue row whose job had no task, or a task with
         * no task type, fell off the end of this method having changed no status, written no audit
         * line and sent no notification. It then stayed in Queue for ever, at the head of a capped
         * fetch, and because getCountForInQueueJobByJobId counts Queue rows the job was treated as
         * permanently busy: every later slot became "skip, already in queue". Nothing recovers it
         * either -- reconcileStalledRuns only looks at Start and Running, and both Run now and
         * Skip next refuse a job whose running status is Queue -- so only direct SQL got it back.
         */
        if (isNull(sourceTask)) {
            this.changeStatusForLastJob(jobQueue, String.format(
                "Job %s has no task attached, so there is nothing to dispatch.", jobQueue.getJobId()));
            return;
        }
        if (isNull(sourceTask.getSourceTaskType())) {
            this.changeStatusForLastJob(jobQueue, String.format(
                "Job %s has no task type configured, so there is no broker to dispatch it to.", jobQueue.getJobId()));
            return;
        }
        try {
            SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
            if (sourceTaskType.getStatus().equals(Status.Active)) {
                String queueTopicPartition = sourceTaskType.getQueueTopicPartition();
                Optional<KafkaTopicPartitionUtil.Parsed> parsed = KafkaTopicPartitionUtil.parse(queueTopicPartition);
                if (parsed.isPresent()) {
                    String topic = parsed.get().getTopic();
                    String partition = parsed.get().getPartition();

                    String key = UUID.randomUUID().toString();
                    String payload = this.getSourceJobDetail(sourceJob, jobQueue);
                    try {

                        KafkaTemplate<String, String> template = this.kafkaTemplateProvider.getTemplate(
                            this.kafkaConnectionResolver.resolve(sourceJob.getTenantId(), sourceTaskType.getSourceTaskTypeId()));
                        if (partition.contains(ProcessUtil.START)) {
                            template.send(topic, key, payload)
                                .addCallback(
                                    result -> this.handleSendSuccess(result, payload, sourceJob, jobQueue),
                                    ex -> this.handleSendFailure(ex, payload, sourceJob, jobQueue)
                                );
                        } else {
                            template.send(topic, Integer.valueOf(partition), key, payload)
                                .addCallback(
                                    result -> this.handleSendSuccess(result, payload, sourceJob, jobQueue),
                                    ex -> this.handleSendFailure(ex, payload, sourceJob, jobQueue)
                                );
                        }
                    } catch (Exception ex) {
                        logger.error("Unexpected exception while sending message=[{}]: {}", payload, ex.getMessage());
                        handleSendFailure(ex, payload, sourceJob, jobQueue);
                    }
                    return;
                }
                logger.error("Regex does not match.");
                // The configured value is an argument, not part of the format string: it is typed
                // by an operator and a '%' in it would otherwise be read as a conversion.
                this.changeStatusForLastJob(jobQueue, String.format(
                    "Broker configuration is invalid for job %s: %s", jobQueue.getJobId(), queueTopicPartition));
                return;
            }
            this.changeStatusForLastJob(jobQueue, String.format("Broker is not active for job %s.", jobQueue.getJobId()));
        } catch (Exception ex) {
            // Retryable, and the only site in this method that is. Everything above fails because
            // the job is configured wrong -- no task, no task type, an unparseable topic, a task
            // type switched off -- and none of that changes by trying again. This catch is where
            // building the producer or resolving the tenant's connection threw, which is the
            // transient case: a broker that was briefly unreachable lands here.
            //
            // It also no longer claims the broker is inactive. That was the message whatever the
            // exception was, so a connection timeout and a deliberately disabled task type were
            // reported identically, and the one sentence a person gets was wrong for most of them.
            this.changeStatusForLastJob(jobQueue, String.format(
                "Job %s could not be dispatched: %s", jobQueue.getJobId(), reasonFor(ex)), true);
            logger.error("Error in pushMessageToQueue: {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    private void handleSendSuccess(SendResult<String, String> result, String payload, SourceJob sourceJob, JobQueue jobQueue) {
        long offset = result.getRecordMetadata().offset();
        logger.info("Sent message=[{}] with offset=[{}]", payload, offset);

        jobQueue.setJobSend(true);
        jobQueue.setJobStatus(JobStatus.Start);
        // The payload is NOT in here. jobStatusMessage is what the Recent runs list and the job
        // row put in front of a person, and a run's whole task XML -- four hundred characters of
        // escaped markup -- pushed everything worth reading off the end of the line. It is already
        // written to the application log a line above, which is where a payload belongs.
        jobQueue.setJobStatusMessage("Handed to the worker queue at offset " + offset + ".");
        this.transactionService.updateJobQueue(jobQueue);
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Start);

        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format(
            "Job %s handed to the worker queue at offset %s.", sourceJob.getJobId(), offset));
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
    }

    private void handleSendFailure(Throwable ex, String payload, SourceJob sourceJob, JobQueue jobQueue) {
        logger.error("Unable to send message=[{}] due to: {}", payload, ex.getMessage());

        jobQueue.setJobSend(false);
        // Reason first and payload nowhere. This read "Job 2417 unable to send message=[{...four
        // hundred characters of escaped task XML...}] due to: Failed to construct kafka producer",
        // so the only part anyone needs -- why it failed -- sat past the end of a two-line clamp
        // and was reachable solely by hovering for the title attribute. The payload is logged
        // immediately above.
        String reason = reasonFor(ex);
        jobQueue.setJobStatusMessage("Could not hand this run to the worker queue: " + reason);

        this.changeStatusForLastJob(jobQueue, String.format(
            "Job %s could not be handed to the worker queue: %s", sourceJob.getJobId(), reason), true);
    }

    /**
     * A failure as a sentence someone can act on, rather than as a stack trace's toString.
     *
     * The root cause carries the useful sentence: a Kafka send failure arrives wrapped, and the
     * outer message is routinely less specific than the thing that actually went wrong. Taken as
     * getMessage() rather than toString() because toString() prefixes the fully-qualified class
     * name, and "org.apache.kafka.common.KafkaException: Failed to construct kafka producer" tells
     * a person nothing the second half does not.
     *
     * Falls back to the class's simple name when the root cause carries no message at all, so the
     * status line never reads "Could not hand this run to the worker queue: null".
     */
    private static String reasonFor(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
            ? root.getClass().getSimpleName()
            : message.trim();
    }

    /**
     * Closes a run as Failed with the reason already written out.
     *
     * The reason arrives finished, and is written down as given. This used to take a template and
     * run String.format over it with the job id, which worked for the callers that hand it a
     * literal with one %s in it and was a trap for the two that do not. handleSendFailure builds
     * its message first, because it has the payload and the broker's own error to put in it, and
     * that payload is the task's XML -- so a single literal '%' anywhere in a task payload (a
     * percentage in a report parameter is enough) made String.format throw
     * UnknownFormatConversionException from the first line of the one method whose job is to
     * record why a run failed. The throw escaped through the callback into Kafka's listener, so
     * the status was never changed, no audit line was written and no notification was sent: the
     * run stayed in Queue with no reason recorded, exactly when the reason was the thing needed.
     * The invalid-broker caller had the same shape, concatenating an operator-typed
     * topic:partition string into the template.
     */
    private void changeStatusForLastJob(JobQueue jobQueue, String statusMessage) {
        this.changeStatusForLastJob(jobQueue, statusMessage, false);
    }

    /**
     * As above, but offering the run another attempt first when the failure is one that a retry
     * could plausibly clear.
     *
     * Retryable is passed per call site rather than assumed, because the two failures that reach
     * here are opposites. A broker that would not take the message is transient -- the same
     * payload sent a minute later usually goes. A job that has been deleted or deactivated is not:
     * the dispatcher will find it missing again on every attempt, and retrying only delays telling
     * somebody by the length of the backoff while holding the job's one in-flight slot.
     */
    private void changeStatusForLastJob(JobQueue jobQueue, String statusMessage, boolean retryable) {
        // Ordered so the failure is only announced once the run has genuinely run out of attempts.
        // Marking Failed first and retrying afterwards would put a Failed status, an audit line and
        // -- for a job with fail mail on -- an email in front of somebody for a run that is about
        // to be attempted again, which is the noise this feature exists to remove.
        if (retryable && this.bulkAction.scheduleRetry(jobQueue, statusMessage)) {
            return;
        }
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
        this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed, statusMessage);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), statusMessage);
        this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId());
        Optional<SourceJob> sourceJobForFailMail = this.transactionService.findByJobId(jobQueue.getJobId());
        if (sourceJobForFailMail.isPresent() && sourceJobForFailMail.get().isFailJob()) {
            this.emailMessagesFactory.sendSourceJobEmail(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Failed);
        }
    }

    private String getSourceJobDetail(SourceJob sourceJob, JobQueue jobQueue) {
        JobPayloadDTO dto = new JobPayloadDTO();
        dto.setJobQueueId(jobQueue.getJobQueueId());
        dto.setJobId(jobQueue.getJobId());
        // The run's own proof for its callbacks, minted and saved here -- before the send below,
        // so the server knows the token before any worker can echo it. See RunCallbackTokens.
        dto.setCallbackToken(this.runCallbackTokens.issue(jobQueue));
        dto.setAttempt(Math.max(1, jobQueue.getAttempt()));
        if (!ProcessUtil.isNull(sourceJob.getTaskDetail())) {
            Long homePageLookupId = ProcessUtil.parseLongOrNull(sourceJob.getTaskDetail().getHomePageId());
            if (homePageLookupId != null) {
                dto.setHomePageId(this.transactionService.findLookupValueByLookupId(homePageLookupId));
            }
            // pipelineId is no longer a PIPELINE_IDS lookup row id -- Task Forms now define a
            // pipeline directly by its own id string (the same one Source Task's Pipeline
            // picker offers and Pipeline.pipelineId is keyed on), so it goes straight through
            // rather than being resolved through the lookup table the way homePageId still is.
            String pipelineId = sourceJob.getTaskDetail().getPipelineId();
            if (!ProcessUtil.isNull(pipelineId)) {
                dto.setPipelineId(pipelineId.trim());
            }
            dto.setTaskPayload(sourceJob.getTaskDetail().getTaskPayload());
        }
        dto.setPriority(sourceJob.getPriority());
        return dto.toString();
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}