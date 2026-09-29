package process.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** etl_job's job_inbox_trigger and inbox_arrival (V185). Row security keeps each to the session's workspace as well. */
public interface InboxTriggerStore {

    String STARTED = "Started";
    String SKIPPED = "Skipped";

    /** A job's inbox trigger. */
    final class Trigger {
        public final long jobId;
        public final long tenantId;
        public final boolean enabled;
        public final String filePattern;
        public final Instant dateUpdated;

        public Trigger(long jobId, long tenantId, boolean enabled, String filePattern, Instant dateUpdated) {
            this.jobId = jobId;
            this.tenantId = tenantId;
            this.enabled = enabled;
            this.filePattern = filePattern;
            this.dateUpdated = dateUpdated;
        }
    }

    /** The workspace's enabled triggers, by job. */
    List<Trigger> enabledFor(long tenantId);

    Optional<Trigger> find(long jobId);

    /** Creates or replaces a job's trigger (its workspace is the job's), stamped with who did it. */
    void save(long jobId, boolean enabled, String filePattern, Long actor);

    boolean delete(long jobId);

    /** Whether this arrival's outcome for this job is already recorded. */
    boolean recorded(String arrivalId, long jobId);

    /**
     * Records an arrival's outcome for a job. A Started row is a plain insert -- in the transaction that made its run, so
     * a duplicate refuses both; a Skipped row keeps the first outcome recorded and is otherwise a no-op.
     */
    void record(InboxArrival arrival, long jobId, String outcome, String reason, Long jobQueueId);

    /** A job's arrivals, newest first, as the console reads them. */
    List<Map<String, Object>> arrivals(long jobId, int limit);
}
