package process.inbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.OneRunInFlight;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.security.JobOwnership;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Core's half of the inbox (MIG-239): a job may be started by a file arriving in its workspace's inbox.
 *
 * <p>An arrival goes to every job of its workspace whose enabled trigger takes the file (a glob on its name, or every
 * file), each ONCE: inbox_arrival is unique per arrival and job, so a redelivered event finds its outcome recorded and
 * does nothing. MIG-360: an arrival is never dropped. It is recorded Waiting, and the job's next run takes it at once
 * when the job is free -- its own run, with the file on it (input_bucket, input_key) -- or, when a run of the job is in
 * flight (Queue, Start, Running), when that run ends: {@link #startNext} takes the job's oldest waiting files, as many
 * as the trigger's batch size (1: a run per file), into one run. The queue sweep ({@link InboxQueueSweep}) calls it for
 * every job with waiting files and nothing in flight, so whichever way a run ended, the next starts within seconds.
 * Run now's other rules hold:
 * <ul>
 *   <li>a job that is gone or not Active does not run: the file is recorded Skipped with the reason (and files that
 *       waited for a job switched off since are Skipped then, saying so);</li>
 *   <li>a Suspended or Inactive workspace's jobs are paused (owner decision 2026-09-24): its files wait, and start
 *       once the workspace is active again.</li>
 * </ul>
 * Starting is serialised per job (an advisory lock for the transaction), and the one-in-flight index still refuses a
 * racing second run (Run now, the scheduler): the files then simply wait for that run. The listener has no signed-in
 * caller: it runs all of this as the event's workspace (RowSecurity.forTenant), so another workspace's triggers and
 * jobs are not even visible to it.
 *
 * <p>The console's side -- a job's trigger and its arrivals -- follows JobOwnership: whoever may see the job may set its
 * trigger (a tenant user their own jobs, an administrator every job of the workspace); anything else reads as not found.
 */
@Service
public class InboxTriggerService {

    static final String JOB_NOT_FOUND = "SourceJob not found with jobId.";
    static final int DEFAULT_ARRIVALS = 50;
    static final int MAX_ARRIVALS = 200;
    static final String WAITS = "A run of this job was in flight when this file arrived; it waits, and the job's next run takes it.";
    static final String GONE = "The job no longer exists.";

    /** What one arrival did to one job. */
    public enum Outcome { STARTED, WAITING, SKIPPED, ALREADY_RECORDED }

    private static final Logger logger = LoggerFactory.getLogger(InboxTriggerService.class);

    private final InboxTriggerStore store;
    private final ProducerBulkEngine engine;
    private final TransactionServiceImpl jobs;
    private final TransactionTemplate transactions;

    public InboxTriggerService(InboxTriggerStore store, ProducerBulkEngine engine, TransactionServiceImpl jobs,
        PlatformTransactionManager transactionManager) {
        this.store = store;
        this.engine = engine;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---- an arrival ----------------------------------------------------------------------------------------------------

    /** Hands the arrival to every job its workspace set to trigger on it, once. Run as that workspace (RowSecurity.forTenant). */
    public List<Outcome> onArrival(InboxArrival arrival) {
        List<Outcome> outcomes = new ArrayList<>();
        for (InboxTriggerStore.Trigger trigger : this.store.enabledFor(arrival.getTenantId())) {
            if (trigger.tenantId != arrival.getTenantId() || !InboxTriggers.matches(trigger.filePattern, arrival.getFileName())) {
                continue;
            }
            outcomes.add(this.accept(arrival, trigger.jobId));
        }
        return outcomes;
    }

    private Outcome accept(InboxArrival arrival, long jobId) {
        if (this.store.recorded(arrival.getArrivalId(), jobId)) {
            logger.info("Inbox arrival {} was already handled for job {}; nothing more to do.", arrival.getArrivalId(), jobId);
            return Outcome.ALREADY_RECORDED;
        }
        Optional<SourceJob> job = this.jobs.findByJobId(jobId);
        String refusal = refusal(job, arrival.getTenantId());
        if (refusal != null) {
            this.transactions.executeWithoutResult(status -> this.store.record(arrival, jobId, InboxTriggerStore.SKIPPED, refusal, null));
            logger.info("Inbox arrival {} did not start job {}: {}", arrival.getArrivalId(), jobId, refusal);
            return Outcome.SKIPPED;
        }
        Boolean recorded = this.transactions.execute(status -> this.store.record(arrival, jobId, InboxTriggerStore.WAITING, WAITS, null));
        if (!Boolean.TRUE.equals(recorded)) {
            return Outcome.ALREADY_RECORDED;
        }
        Started next = this.startNext(jobId);
        if (next.arrivalIds.contains(arrival.getArrivalId())) {
            return Outcome.STARTED;
        }
        logger.info("Inbox arrival {} waits for job {}'s next run ({} file(s) waiting).", arrival.getArrivalId(), jobId,
            this.store.waitingCount(jobId));
        return Outcome.WAITING;
    }

    /** What {@link #startNext} did: the run it made (null for none) and the arrivals it took. */
    public static final class Started {
        static final Started NONE = new Started(null, Collections.emptyList(), null);

        public final Long jobQueueId;
        public final List<String> arrivalIds;
        /** Why nothing started, for the log: in flight, paused, nothing waiting. */
        public final String why;

        Started(Long jobQueueId, List<String> arrivalIds, String why) {
            this.jobQueueId = jobQueueId;
            this.arrivalIds = arrivalIds;
            this.why = why;
        }

        @Override
        public String toString() {
            return this.jobQueueId == null ? "nothing started" + (this.why == null ? "" : " (" + this.why + ")")
                : String.format("run %d with %d file(s)", this.jobQueueId, this.arrivalIds.size());
        }
    }

    /**
     * MIG-360: the job's next run from its waiting files, when the job may run now: the oldest, as many as the trigger's
     * batch size, into one run named after the first (input_bucket, input_key; the rest are the run's too -- keysOf).
     * Nothing when no file waits, a run is in flight or the workspace is paused; a job that is gone or switched off has
     * its waiting files Skipped. Serialised per job; run as the job's workspace.
     */
    public Started startNext(long jobId) {
        try {
            Started started = this.transactions.execute(status -> {
                this.store.lockJob(jobId);
                List<InboxTriggerStore.Waiting> peek = this.store.waiting(jobId, 1);
                if (peek.isEmpty()) {
                    return new Started(null, Collections.emptyList(), "no file waits");
                }
                Optional<SourceJob> job = this.jobs.findByJobId(jobId);
                String refusal = refusal(job, job.map(SourceJob::getTenantId).orElse(null));
                if (refusal != null) {
                    int skipped = this.store.skipWaiting(jobId, refusal);
                    logger.info("Job {}: {} waiting inbox file(s) skipped: {}", jobId, skipped, refusal);
                    return new Started(null, Collections.emptyList(), refusal);
                }
                Optional<String> paused = this.engine.workspacePause(job.get().getTenantId());
                if (paused.isPresent()) {
                    return new Started(null, Collections.emptyList(), "the workspace is " + paused.get());
                }
                if (this.store.inFlight(jobId)) {
                    return new Started(null, Collections.emptyList(), "a run is in flight");
                }
                int batch = this.store.find(jobId).map(t -> t.batchSize).orElse(1);
                List<InboxTriggerStore.Waiting> files = this.store.waiting(jobId, Math.max(1, Math.min(batch, InboxTriggerStore.MAX_BATCH)));
                InboxTriggerStore.Waiting first = files.get(0);
                List<String> arrivalIds = new ArrayList<>(files.size());
                List<Long> rows = new ArrayList<>(files.size());
                List<String> names = new ArrayList<>(files.size());
                for (InboxTriggerStore.Waiting file : files) {
                    arrivalIds.add(file.arrivalId);
                    rows.add(file.inboxArrivalId);
                    names.add(file.fileName);
                }
                JobQueue run = this.engine.addInboxJobInQueue(job.get(), first.bucket, first.key, names, arrivalIds);
                this.store.started(rows, run.getJobQueueId());
                return new Started(run.getJobQueueId(), arrivalIds, null);
            });
            return started == null ? Started.NONE : started;
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isViolation(failed)) {
                // Another start (Run now, the scheduler) took the slot between the check and the insert: the files wait for it.
                return new Started(null, Collections.emptyList(), "another run took the slot");
            }
            throw failed;
        }
    }

    /** Run now's rules that stop a job for good, in its words; null when the job may run. */
    private static String refusal(Optional<SourceJob> job, Long tenantId) {
        if (!job.isPresent() || job.get().getTenantId() == null || tenantId == null || !job.get().getTenantId().equals(tenantId)) {
            return GONE;
        }
        if (job.get().getJobStatus() != Status.Active) {
            return String.format("The job is not active (%s), so the file did not start it.", job.get().getJobStatus());
        }
        return null;
    }

    // ---- the console ---------------------------------------------------------------------------------------------------

    /** A job's trigger: whether it has one, whether it is on, its file pattern, batch size and how many files wait. */
    public ResponseDto trigger(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        Optional<InboxTriggerStore.Trigger> trigger = this.store.find(job.get().getJobId());
        return new ResponseDto(SUCCESS, trigger.isPresent() ? "Inbox trigger fetched successfully." : "This job has no inbox trigger.",
            InboxTriggerView.of(job.get().getJobId(), trigger.orElse(null), this.store.waitingCount(job.get().getJobId())));
    }

    /** Sets a job's trigger: on or off, and which files (a glob on the name; blank for every file). */
    public ResponseDto save(Long jobId, Boolean enabled, String filePattern) {
        return this.save(jobId, enabled, filePattern, null);
    }

    /**
     * As above, with how many waiting files one run takes (MIG-360): 1..{@value InboxTriggerStore#MAX_BATCH}; absent keeps
     * the trigger's (1 for a new one).
     */
    public ResponseDto save(Long jobId, Boolean enabled, String filePattern, Integer batchSize) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        String pattern;
        try {
            pattern = InboxTriggers.validPattern(filePattern);
        } catch (IllegalArgumentException refused) {
            return new ResponseDto(ERROR, refused.getMessage());
        }
        if (batchSize != null && (batchSize < 1 || batchSize > InboxTriggerStore.MAX_BATCH)) {
            return new ResponseDto(ERROR, String.format("batchSize is how many waiting files one run takes: 1 to %d.",
                InboxTriggerStore.MAX_BATCH));
        }
        int batch = batchSize != null ? batchSize : this.store.find(job.get().getJobId()).map(t -> t.batchSize).orElse(1);
        boolean on = enabled == null || enabled;
        this.store.save(job.get().getJobId(), on, pattern, batch, TenantContext.getAppUserId());
        String files = pattern == null ? "Every file that arrives in the inbox" : String.format("Every file named like %s that arrives in the inbox",
            pattern);
        String batching = batch == 1 ? " Files that arrive while a run is going wait, and each starts its own run in turn."
            : String.format(" Files that arrive while a run is going wait; the next run takes up to %d of them.", batch);
        return new ResponseDto(SUCCESS, on ? files + " starts this job." + batching
            : "The inbox trigger is off; files that arrive do not start this job.",
            InboxTriggerView.of(job.get().getJobId(), this.store.find(job.get().getJobId()).orElse(null),
                this.store.waitingCount(job.get().getJobId())));
    }

    public ResponseDto delete(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        return new ResponseDto(SUCCESS, this.store.delete(job.get().getJobId()) ? "Inbox trigger removed." : "This job has no inbox trigger.");
    }

    /**
     * What the inbox's files did to this job, newest first: Started (the run), Waiting (its place in the line: 1 is taken
     * by the next run) or Skipped (why).
     */
    public ResponseDto arrivals(Long jobId, int limit) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        int size = limit <= 0 ? DEFAULT_ARRIVALS : Math.min(limit, MAX_ARRIVALS);
        List<Map<String, Object>> arrivals = this.store.arrivals(job.get().getJobId(), size);
        return new ResponseDto(SUCCESS, String.format("%d inbox arrival(s).", arrivals.size()), arrivals);
    }

    private Optional<SourceJob> visibleJob(Long jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return this.jobs.findByJobId(jobId).filter(JobOwnership::isVisibleToCaller);
    }
}
