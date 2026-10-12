package process.documents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * etl_job's job_document_trigger and document_approval (V207). Row security keeps each to the session's workspace as well.
 */
public interface DocumentTriggerStore {

    String STARTED = "Started";
    String SKIPPED = "Skipped";
    /** The job was busy (or its workspace paused): the job's next run takes the document. */
    String WAITING = "Waiting";

    /** How many waiting documents one run may take (job_document_trigger.batch_size). */
    int MAX_BATCH = 50;

    /** A job's document trigger. */
    final class Trigger {
        public final long jobId;
        public final long tenantId;
        public final long documentTypeId;
        public final boolean enabled;
        public final int batchSize;
        public final Instant dateUpdated;

        public Trigger(long jobId, long tenantId, long documentTypeId, boolean enabled, int batchSize, Instant dateUpdated) {
            this.jobId = jobId;
            this.tenantId = tenantId;
            this.documentTypeId = documentTypeId;
            this.enabled = enabled;
            this.batchSize = batchSize;
            this.dateUpdated = dateUpdated;
        }
    }

    /** An approved document waiting for its job's next run. */
    final class Waiting {
        public final long documentApprovalId;
        public final long extractionId;
        public final int typeVersion;

        public Waiting(long documentApprovalId, long extractionId, int typeVersion) {
            this.documentApprovalId = documentApprovalId;
            this.extractionId = extractionId;
            this.typeVersion = typeVersion;
        }
    }

    /** The workspace's enabled triggers on this document type, by job. */
    List<Trigger> enabledFor(long tenantId, long documentTypeId);

    Optional<Trigger> find(long jobId);

    /** Creates or replaces a job's trigger (its workspace is the job's), stamped with who did it. */
    void save(long jobId, long documentTypeId, boolean enabled, int batchSize, Long actor);

    boolean delete(long jobId);

    /** Whether this document's (and version's) outcome for this job is already recorded. */
    boolean recorded(long extractionId, int typeVersion, long jobId);

    /** Records an approval's outcome for a job; keeps the first outcome recorded (a no-op then). Answers whether it wrote. */
    boolean record(DocumentApproved approved, long jobId, String outcome, String reason);

    /** Serialises the starts of one job's next run for the rest of the caller's transaction (an advisory lock on the job). */
    void lockJob(long jobId);

    /** Whether the job has a run in flight (Queue, Start, Running) -- the one-in-flight index's own predicate. */
    boolean inFlight(long jobId);

    /** The job's oldest waiting documents, at most {@code limit}, in the order they were approved. */
    List<Waiting> waiting(long jobId, int limit);

    /** These waiting documents are started by this run; answers how many were. */
    int started(List<Long> documentApprovalIds, long jobQueueId);

    /** Every waiting document of the job is Skipped, with the reason (the job is gone or was switched off). */
    int skipWaiting(long jobId, String reason);

    /** How many documents wait for the job. */
    int waitingCount(long jobId);

    /** A job's approvals, newest first, as the console reads them. */
    List<Map<String, Object>> approvals(long jobId, int limit);
}
