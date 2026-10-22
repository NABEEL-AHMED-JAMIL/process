package process.pipeline.backing;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which run took which item of a "since the last run" read (run_read_take, V221; owner 2026-10-21). Runs a workflow's
 * requests start run side by side (V220), each from the job's same watermark, so each would read the same new items. An
 * item a run in flight took is not another run's; an item a failed or interrupted run took is free again, read by the
 * next run even when a later run's watermark passed it. A source names what is read: "s3|alias|prefix",
 * "documents|type".
 */
public interface ReadTakes {

    /**
     * Takes for run {@code jobQueueId}, in the order given, at most {@code limit} of the items no other run in flight has
     * taken that are {@code fresh} (past the job's watermark) or were taken before by a run that did not complete (or by
     * this run, on an earlier try). One job, step and source at a time: a second run asking at the same moment waits and
     * then sees this one's takes.
     */
    Taken take(long jobQueueId, long jobId, String stepKey, String source, List<String> items, Set<String> fresh, int limit);

    /** The items runs that failed or were interrupted took, and no run has taken since: the next run reads them again. */
    List<String> released(long jobId, String stepKey, String source);

    /** What a take answered. */
    final class Taken {
        /** Taken by this run, in the order given. */
        public final List<String> items;
        /** Left alone: another run in flight took them. */
        public final int heldByOthers;
        /** More could have been taken than the limit allowed. */
        public final boolean more;

        public Taken(List<String> items, int heldByOthers, boolean more) {
            this.items = Collections.unmodifiableList(items);
            this.heldByOthers = heldByOthers;
            this.more = more;
        }
    }
}
