package process.util;

import process.model.enums.JobStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Telling a slow run apart from one that has stopped reporting (MIG-63).
 *
 * This rule used to live only in one Angular app (scheduler1/next, stalled.ts), which invented a
 * "stalled" state purely to render it; the backend had no such idea. It is carried across unchanged
 * so both frontends can read one verdict from the server: a run in flight -- Queue, Start or Running
 * -- whose lastJobRun is more than thirty minutes behind the application clock. Strictly more, as
 * there. No lastJobRun is not a stall, and neither is one in the future: that is the application and
 * the database disagreeing about the time, and calling it a stall would flag every job on a host
 * whose clock drifts.
 *
 * This only DESCRIBES a run; it closes nothing. Closing is the stall sweep's
 * (ProducerBulkEngine.reconcileStalledRuns): after six hours of silence, or on its next pass once a
 * genuine report has been refused for an expired token.
 */
public final class RunStall {

    /** Far longer than any run here takes, so crossing it means quiet rather than slow. */
    public static final Duration STALLED_AFTER = Duration.ofMinutes(30);

    private RunStall() {
    }

    public static boolean isStalled(JobStatus runningStatus, LocalDateTime lastJobRun, LocalDateTime now) {
        if (runningStatus == null || !runningStatus.isInFlight() || lastJobRun == null || now == null) {
            return false;
        }
        // A future lastJobRun gives a negative age, which is never past the window.
        return Duration.between(lastJobRun, now).compareTo(STALLED_AFTER) > 0;
    }
}
