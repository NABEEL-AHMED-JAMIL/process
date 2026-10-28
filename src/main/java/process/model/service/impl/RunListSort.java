package process.model.service.impl;

import process.model.enums.JobStatus;
import process.util.ListSort;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owner 2026-10-27 ("add a sort on each table"): the columns a list of runs sorts by -- a schedule's run history
 * (sourceJob.json/fetchSourceJobQueueListWithJobId) and Runs › Latest (message.json/fetchLogs) -- as the console shows them:
 * Run, Queued, Started (else skipped at), Ended, Duration and Status. The SQL is written here, over job_queue aliased jq; a
 * request only picks a key.
 */
public final class RunListSort {

    private RunListSort() {
    }

    /** The run's id: the tiebreak, and the list's own order (newest first). */
    static final String ID = "jq.job_queue_id";

    /**
     * Problems first, then in progress, waiting and done -- the order the console's status chips sort in. Written from the
     * enum, so a new status is a compile-time question, not a run sorted as "other".
     */
    static final List<JobStatus> STATUS_ORDER = Collections.unmodifiableList(Arrays.asList(JobStatus.Failed, JobStatus.Interrupt,
        JobStatus.Missed, JobStatus.Running, JobStatus.Start, JobStatus.Queue, JobStatus.Skip, JobStatus.Completed));

    static final String STATUS_RANK;

    public static final Map<String, ListSort.Column> COLUMNS;

    static {
        StringBuilder rank = new StringBuilder("(CASE jq.job_status");
        for (int i = 0; i < STATUS_ORDER.size(); i++) {
            rank.append(" WHEN '").append(STATUS_ORDER.get(i).name()).append("' THEN ").append(i);
        }
        STATUS_RANK = rank.append(" ELSE ").append(STATUS_ORDER.size()).append(" END)").toString();
        Map<String, ListSort.Column> columns = new LinkedHashMap<>();
        columns.put("run", ListSort.Column.of(ID));
        columns.put("queued", ListSort.Column.of("jq.date_created"));
        // The Started cell: the start, else when the run was skipped. Spelled as V236's index is.
        columns.put("started", ListSort.Column.nullable("(COALESCE(jq.start_time, jq.skip_time))"));
        columns.put("ended", ListSort.Column.nullable("jq.end_time"));
        // Null while a run has not both started and ended, as the console's Duration cell is "—".
        columns.put("duration", ListSort.Column.nullable("(jq.end_time - jq.start_time)"));
        columns.put("status", ListSort.Column.of(STATUS_RANK));
        COLUMNS = Collections.unmodifiableMap(columns);
    }

    /** The request's sort, or null for the newest-first order. */
    public static ListSort.Choice parse(String sort, String dir) {
        return ListSort.parse(sort, dir, COLUMNS.keySet());
    }

    /** The ORDER BY of a sorted list of runs (no "order by" word). */
    static String orderBy(ListSort.Choice choice) {
        return ListSort.orderBy(choice, COLUMNS, ID);
    }

    /**
     * One schedule's runs in the sort's order, a window at a time: offset paging, since a keyset on the beforeId cursor only
     * walks the newest-first order. Native, as findRecentByJobId is (the job was shown to be the caller's first); V236's
     * per-job indexes serve Queued both ways and Started, Ended and Duration newest / longest first.
     */
    static String jobHistory(ListSort.Choice choice) {
        return "select jq.* from job_queue jq where jq.job_id = :jobId order by " + orderBy(choice) + " limit :limit offset :offset";
    }
}
