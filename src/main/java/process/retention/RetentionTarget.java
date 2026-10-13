package process.retention;

import java.time.Instant;

/**
 * MIG-354: one class's data, as a pass sees it: what is older than a cutoff (the preview), and removing it a bounded
 * batch at a time. The preview and the removal read the same items -- the preview's count is what the batches remove,
 * holds and all -- so "it would remove N" is what a pass then does.
 */
public interface RetentionTarget {

    /** What is eligible now: older than the cutoff, outside every hold. */
    Tally preview(long tenantId, Instant cutoff, Holds holds) throws Unavailable;

    /** Removes at most {@code limit} eligible items, oldest first; says what it removed and whether more is eligible. */
    Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) throws Unavailable;

    /** The preview of one class. bytes is null where the class has no files to size. */
    final class Tally {
        public long count;
        public Long bytes;
        public Instant oldest;
        /** Every item of the class in the workspace (eligible or not): the share a pass may remove is measured on it. */
        public long total;

        public static Tally of(long count, Long bytes, Instant oldest, long total) {
            Tally tally = new Tally();
            tally.count = count;
            tally.bytes = bytes;
            tally.oldest = oldest;
            tally.total = total;
            return tally;
        }
    }

    /** What one batch removed. */
    final class Batch {
        public long deleted;
        public long bytes;
        public boolean more;

        public static Batch of(long deleted, long bytes, boolean more) {
            Batch batch = new Batch();
            batch.deleted = deleted;
            batch.bytes = bytes;
            batch.more = more;
            return batch;
        }
    }

    /** The owning service cannot answer (not deployed with retention yet, or not reachable): nothing was removed. */
    class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }
}
