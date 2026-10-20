package process.pipeline.backing;

import java.time.Instant;
import java.util.Optional;

/**
 * How far a job's read_s3 step has read a bucket prefix (run_object_read): each run records the newest object it read --
 * its last-modified time and key. "Only new files since the last run" reads the objects after the newest one a COMPLETED
 * run of the job read with the same step, bucket and prefix; a failed or interrupted run moves nothing, so its files come
 * again.
 */
public interface ObjectReadMarks {

    /** The newest object a completed run of this job read with this step under this bucket and prefix; empty when none has. */
    Optional<Mark> watermark(long jobId, String stepKey, String bucket, String prefix);

    /** Records what this run's step read: up to which object (or the mark it started from) and how many. */
    void record(long jobQueueId, String stepKey, long jobId, String bucket, String prefix, Mark upTo, long objects, int attempt);

    /** An object's place in time order: its last-modified time, then its key. */
    final class Mark implements Comparable<Mark> {
        public final Instant modified;
        public final String key;

        public Mark(Instant modified, String key) {
            this.modified = modified;
            this.key = key;
        }

        @Override
        public int compareTo(Mark other) {
            int byTime = this.modified.compareTo(other.modified);
            return byTime != 0 ? byTime : this.key.compareTo(other.key);
        }
    }
}
