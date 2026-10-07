package process.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * etl_job's job_inbox_trigger and inbox_arrival (V185, V204). Row security keeps each to the session's workspace as well.
 */
public interface InboxTriggerStore {

    String STARTED = "Started";
    String SKIPPED = "Skipped";
    /** MIG-360: the file arrived while its job was busy; the job's next run takes it. */
    String WAITING = "Waiting";

    /** How many waiting files one run may take (job_inbox_trigger.batch_size). */
    int MAX_BATCH = 50;

    /** A job's inbox trigger. */
    final class Trigger {
        public final long jobId;
        public final long tenantId;
        public final boolean enabled;
        public final String filePattern;
        public final Instant dateUpdated;
        /** MIG-360: how many waiting files one run takes, 1..{@value #MAX_BATCH}. */
        public final int batchSize;

        public Trigger(long jobId, long tenantId, boolean enabled, String filePattern, Instant dateUpdated) {
            this(jobId, tenantId, enabled, filePattern, dateUpdated, 1);
        }

        public Trigger(long jobId, long tenantId, boolean enabled, String filePattern, Instant dateUpdated, int batchSize) {
            this.jobId = jobId;
            this.tenantId = tenantId;
            this.enabled = enabled;
            this.filePattern = filePattern;
            this.dateUpdated = dateUpdated;
            this.batchSize = batchSize;
        }
    }

    /** MIG-360: a file waiting for its job's next run. */
    final class Waiting {
        public final long inboxArrivalId;
        public final String arrivalId;
        public final String bucket;
        public final String key;
        public final String fileName;

        public Waiting(long inboxArrivalId, String arrivalId, String bucket, String key, String fileName) {
            this.inboxArrivalId = inboxArrivalId;
            this.arrivalId = arrivalId;
            this.bucket = bucket;
            this.key = key;
            this.fileName = fileName;
        }
    }

    /** The workspace's enabled triggers, by job. */
    List<Trigger> enabledFor(long tenantId);

    Optional<Trigger> find(long jobId);

    /** Creates or replaces a job's trigger (its workspace is the job's), stamped with who did it. */
    default void save(long jobId, boolean enabled, String filePattern, Long actor) {
        this.save(jobId, enabled, filePattern, 1, actor);
    }

    /** As above, with how many waiting files one run takes (MIG-360). */
    void save(long jobId, boolean enabled, String filePattern, int batchSize, Long actor);

    boolean delete(long jobId);

    /** Whether this arrival's outcome for this job is already recorded. */
    boolean recorded(String arrivalId, long jobId);

    /**
     * Records an arrival's outcome for a job. A Started row is a plain insert -- in the transaction that made its run, so
     * a duplicate refuses both; a Skipped or Waiting row keeps the first outcome recorded and is otherwise a no-op.
     * Answers whether the row was written.
     */
    boolean record(InboxArrival arrival, long jobId, String outcome, String reason, Long jobQueueId);

    /**
     * MIG-360: serialises the starts of one job's next run, for the rest of the caller's transaction (an advisory lock on
     * the job): an arrival and the queue sweep, on any replica, never both take the same waiting files.
     */
    void lockJob(long jobId);

    /** MIG-360: whether the job has a run in flight (Queue, Start, Running) -- the one-in-flight index's own predicate. */
    boolean inFlight(long jobId);

    /** MIG-360: the job's oldest waiting files, at most {@code limit}, in the order they arrived. */
    List<Waiting> waiting(long jobId, int limit);

    /** MIG-360: these waiting files are started by this run; answers how many were. */
    int started(List<Long> inboxArrivalIds, long jobQueueId);

    /** MIG-360: every waiting file of the job is Skipped, with the reason (the job is gone or was switched off). */
    int skipWaiting(long jobId, String reason);

    /** MIG-360: the files a run was started for, in the order they arrived (one, or a batch). */
    List<String> keysOf(long jobQueueId);

    /** MIG-360: how many files wait for the job. */
    int waitingCount(long jobId);

    /** A job's arrivals, newest first, as the console reads them. */
    List<Map<String, Object>> arrivals(long jobId, int limit);
}
