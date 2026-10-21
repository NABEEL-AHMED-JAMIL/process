package process.engine;

import java.sql.SQLException;

/**
 * One in-flight run per job, as the database enforces it (P12, MIG-113, MIG-135).
 *
 * getCountForInQueueJobByJobId(jobId) > 0 decided it alone: a plain SELECT COUNT(*) with no lock, a
 * check-then-act that was right only because exactly one dispatcher ran at a time under ShedLock. Two
 * enqueuers, two replicas, or an operator's Run now racing the minute tick could each see 0 and each
 * insert a run -- two workers writing one output folder. {@value #INDEX} (V83) makes the second insert
 * fail instead, and this is how a caller tells that failure apart from any other: the count stays as
 * the cheap pre-filter, the index is the enforcement, and losing to it means exactly what a positive
 * count meant.
 *
 * The in-flight set the index names is the one JobStatus.IN_FLIGHT names; JobStatusInFlightTest holds
 * the changeset's predicate and every native query to it.
 */
public final class OneRunInFlight {

    public static final String INDEX = "ux_job_queue_one_in_flight_per_job";
    /** V220: one run in flight per job and workflow request (a request asking twice starts one run). */
    public static final String WORKFLOW_INDEX = "ux_job_queue_one_in_flight_per_workflow";

    private static final String UNIQUE_VIOLATION = "23505";

    private OneRunInFlight() {
    }

    /** Whether this failure, anywhere down its causes, is the index refusing a second in-flight run. */
    /** V220: the failure is a workflow request's second run of a job while its first is in flight. */
    public static boolean isWorkflowViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException && UNIQUE_VIOLATION.equals(((SQLException) cause).getSQLState())
                && cause.getMessage() != null && cause.getMessage().contains(WORKFLOW_INDEX)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException && UNIQUE_VIOLATION.equals(((SQLException) cause).getSQLState())
                && cause.getMessage() != null && cause.getMessage().contains(INDEX)) {
                return true;
            }
        }
        return false;
    }
}
