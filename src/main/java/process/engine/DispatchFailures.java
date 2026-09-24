package process.engine;

import process.util.BusinessTime;
import org.springframework.transaction.support.TransactionOperations;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * How every dispatch-side phase closes a run it will not send (MIG-134): the pre-dispatch phase, the
 * dispatcher and the relay all close through here, so a declined run is never left in Queue.
 *
 * A run that is neither dispatched nor closed permanently disables its job, because the dispatcher
 * counts Queue, Start and Running rows to decide the job is busy.
 */
public class DispatchFailures {

    private final BulkAction bulkAction;
    private final TransactionServiceImpl transactionService;
    private final JobMail jobMail;
    /** The closing writes -- the retry, or the status, the audit line, the end time and the notice -- land together. */
    private final TransactionOperations transactions;

    public DispatchFailures(BulkAction bulkAction, TransactionServiceImpl transactionService, JobMail jobMail,
        TransactionOperations transactions) {
        this.bulkAction = bulkAction;
        this.transactionService = transactionService;
        this.jobMail = jobMail;
        this.transactions = transactions;
    }

    /**
     * Closes a run as Failed with the reason already written out -- or, when the failure is one a retry
     * could plausibly clear, offers the run another attempt first.
     *
     * The reason arrives finished and is written down as given: it can carry an operator-typed topic or
     * a broker's own error, and a '%' in either used to make String.format throw from the one method
     * whose job is to record why a run failed.
     *
     * Retryable is decided per call site, because the failures that reach here are opposites. A broker
     * that would not take the message is transient; a job deleted, a task unconfigured, an AI step that
     * failed and said the run must -- none of those changes by trying again, and retrying only delays
     * telling somebody by the length of the backoff while holding the job's one in-flight slot.
     *
     * Ordered so the failure is only announced once the run has genuinely run out of attempts: marking
     * Failed first and retrying afterwards would put a Failed status, an audit line and a fail mail in
     * front of somebody for a run that is about to be attempted again.
     */
    public void close(JobQueue jobQueue, String statusMessage, boolean retryable) {
        this.transactions.execute(status -> {
            this.closeNow(jobQueue, statusMessage, retryable);
            return null;
        });
    }

    private void closeNow(JobQueue jobQueue, String statusMessage, boolean retryable) {
        if (retryable && this.bulkAction.scheduleRetry(jobQueue, statusMessage)) {
            return;
        }
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
        this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed, statusMessage);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), statusMessage);
        this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), BusinessTime.now());
        // The run is named so the Failed notice is sent once for this run and attempt.
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId(), jobQueue.getJobQueueId(), true);
        Optional<SourceJob> sourceJobForFailMail = this.transactionService.findByJobId(jobQueue.getJobId());
        if (sourceJobForFailMail.isPresent() && sourceJobForFailMail.get().isFailJob()) {
            this.jobMail.send(SourceJobQueueDto.forEmailNotification(jobQueue), JobStatus.Failed);
        }
    }

    /**
     * A failure as a sentence someone can act on, rather than as a stack trace's toString.
     *
     * The root cause carries the useful sentence: a Kafka send failure arrives wrapped, and the outer
     * message is routinely less specific than the thing that actually went wrong. getMessage() rather
     * than toString(), which prefixes the class name; the class's simple name when there is no message,
     * so the status line never reads "...: null".
     */
    public static String reasonFor(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.trim().isEmpty()
            ? root.getClass().getSimpleName()
            : message.trim();
    }
}
