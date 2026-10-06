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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Core's half of the inbox (MIG-239): a job may be started by a file arriving in its workspace's inbox.
 *
 * <p>An arrival starts every job of its workspace whose enabled trigger takes the file (a glob on its name, or every
 * file) -- each its own run, with the file on it (input_bucket, input_key), and each ONCE: inbox_arrival is unique per
 * arrival and job, so a redelivered event finds its outcome recorded and does nothing. Run now's rules hold, and an
 * arrival they refuse is recorded as Skipped with the reason, not queued for later:
 * <ul>
 *   <li>a job with a run in flight (Queue, Start, Running) is not started again -- the one-in-flight rule
 *       (OneRunInFlight) that Skips a scheduled slot, here too, the index refusing a racing second run as well;</li>
 *   <li>a job that is not Active does not run;</li>
 *   <li>a Suspended or Inactive workspace's jobs are paused (owner decision 2026-09-24).</li>
 * </ul>
 * The listener has no signed-in caller: it runs all of this as the event's workspace (RowSecurity.forTenant), so
 * another workspace's triggers and jobs are not even visible to it.
 *
 * <p>The console's side -- a job's trigger and its arrivals -- follows JobOwnership: whoever may see the job may set its
 * trigger (a tenant user their own jobs, an administrator every job of the workspace); anything else reads as not found.
 */
@Service
public class InboxTriggerService {

    static final String JOB_NOT_FOUND = "SourceJob not found with jobId.";
    static final int DEFAULT_ARRIVALS = 50;
    static final int MAX_ARRIVALS = 200;
    private static final String DUPLICATE = "ux_inbox_arrival_arrival_job";

    /** What one arrival did to one job. */
    public enum Outcome { STARTED, SKIPPED, ALREADY_RECORDED }

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

    /** Starts every job the arrival's workspace set to trigger on it, once. Run as that workspace (RowSecurity.forTenant). */
    public List<Outcome> onArrival(InboxArrival arrival) {
        List<Outcome> outcomes = new ArrayList<>();
        for (InboxTriggerStore.Trigger trigger : this.store.enabledFor(arrival.getTenantId())) {
            if (trigger.tenantId != arrival.getTenantId() || !InboxTriggers.matches(trigger.filePattern, arrival.getFileName())) {
                continue;
            }
            outcomes.add(this.start(arrival, trigger.jobId));
        }
        return outcomes;
    }

    private Outcome start(InboxArrival arrival, long jobId) {
        if (this.store.recorded(arrival.getArrivalId(), jobId)) {
            logger.info("Inbox arrival {} was already handled for job {}; nothing more to do.", arrival.getArrivalId(), jobId);
            return Outcome.ALREADY_RECORDED;
        }
        Optional<SourceJob> job = this.jobs.findByJobId(jobId);
        String refusal = this.refusal(job, arrival);
        if (refusal != null) {
            return this.skip(arrival, jobId, refusal);
        }
        try {
            this.transactions.executeWithoutResult(status -> {
                JobQueue run = this.engine.addInboxJobInQueue(job.get(), arrival.getAlias(), arrival.getKey(), arrival.getFileName(),
                    arrival.getArrivalId());
                this.store.record(arrival, jobId, InboxTriggerStore.STARTED, null, run.getJobQueueId());
            });
            return Outcome.STARTED;
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isViolation(failed)) {
                // Another start (Run now, the enqueuer, another arrival) took the slot between the check and the insert.
                return this.skip(arrival, jobId, inFlight(null));
            }
            if (isDuplicate(failed)) {
                return Outcome.ALREADY_RECORDED;
            }
            throw failed;
        }
    }

    /** Run now's rules, in its words where it has them; null when the job may start. */
    private String refusal(Optional<SourceJob> job, InboxArrival arrival) {
        if (!job.isPresent() || job.get().getTenantId() == null || job.get().getTenantId() != arrival.getTenantId()) {
            return "The job no longer exists.";
        }
        if (job.get().getJobStatus() != Status.Active) {
            return String.format("The job is not active (%s), so the file did not start it.", job.get().getJobStatus());
        }
        Optional<String> paused = this.engine.workspacePause(job.get().getTenantId());
        if (paused.isPresent()) {
            return String.format("This job's workspace is %s, so its runs are paused; the file did not start it.", paused.get());
        }
        if (job.get().getJobRunningStatus() != null && job.get().getJobRunningStatus().isInFlight()) {
            return inFlight(job.get().getJobRunningStatus().name());
        }
        return null;
    }

    private static String inFlight(String status) {
        return "A run of this job was still in flight" + (status == null ? "" : " ('" + status + "')")
            + ", so this file did not start another. Upload it again, or run the job, once that run has finished.";
    }

    private Outcome skip(InboxArrival arrival, long jobId, String reason) {
        this.transactions.executeWithoutResult(status -> this.store.record(arrival, jobId, InboxTriggerStore.SKIPPED, reason, null));
        logger.info("Inbox arrival {} did not start job {}: {}", arrival.getArrivalId(), jobId, reason);
        return Outcome.SKIPPED;
    }

    private static boolean isDuplicate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException && "23505".equals(((SQLException) cause).getSQLState())
                && cause.getMessage() != null && cause.getMessage().contains(DUPLICATE)) {
                return true;
            }
        }
        return false;
    }

    // ---- the console ---------------------------------------------------------------------------------------------------

    /** A job's trigger: whether it has one, whether it is on, and its file pattern. */
    public ResponseDto trigger(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        Optional<InboxTriggerStore.Trigger> trigger = this.store.find(job.get().getJobId());
        return new ResponseDto(SUCCESS, trigger.isPresent() ? "Inbox trigger fetched successfully." : "This job has no inbox trigger.",
            InboxTriggerView.of(job.get().getJobId(), trigger.orElse(null)));
    }

    /** Sets a job's trigger: on or off, and which files (a glob on the name; blank for every file). */
    public ResponseDto save(Long jobId, Boolean enabled, String filePattern) {
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
        boolean on = enabled == null || enabled;
        this.store.save(job.get().getJobId(), on, pattern, TenantContext.getAppUserId());
        return new ResponseDto(SUCCESS, on ? (pattern == null ? "Every file that arrives in the inbox starts this job."
            : String.format("Every file named like %s that arrives in the inbox starts this job.", pattern))
            : "The inbox trigger is off; files that arrive do not start this job.",
            InboxTriggerView.of(job.get().getJobId(), this.store.find(job.get().getJobId()).orElse(null)));
    }

    public ResponseDto delete(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        return new ResponseDto(SUCCESS, this.store.delete(job.get().getJobId()) ? "Inbox trigger removed." : "This job has no inbox trigger.");
    }

    /** What the inbox's files did to this job, newest first: Started (the run) or Skipped (why). */
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
