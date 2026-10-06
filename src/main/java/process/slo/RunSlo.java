package process.slo;

import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The pipeline execution SLI's one classification rule (MIG-196; etl-platform docs/SLO.md): a run that has reached a
 * terminal status is good, bad, or not counted. The live counter (RunOutcomes) and the stored-data report
 * (RunSloReport) both ask this, so the two cannot disagree on what a run was.
 *
 * <ul>
 *   <li>Excluded: Skip and Missed (never attempted), and a run closed by a person, refused on its configuration
 *       before dispatch, or failed by an AI step before dispatch (a refusal: no worker was asked, nothing billed).</li>
 *   <li>Good: Completed.</li>
 *   <li>Bad: Failed or Interrupt otherwise -- the worker's failure after its retries, a decline (MIG-201), a dispatch
 *       that ran out of retries, and the stall sweep's Interrupt (C4).</li>
 * </ul>
 * A run with no recorded reason (it ended before V174) is judged on its status alone.
 */
public enum RunSlo {

    GOOD, BAD, EXCLUDED;

    /** Every status a run leaves the queue in: everything that is not in flight. */
    public static final Set<JobStatus> TERMINAL =
        Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(JobStatus.IN_FLIGHT)));

    /** Reasons that take a run out of the SLI whatever its status. */
    public static final Set<RunEnd> EXCLUDED_REASONS = Collections.unmodifiableSet(
        EnumSet.of(RunEnd.REFUSED, RunEnd.AI_STEP, RunEnd.OPERATOR, RunEnd.SKIPPED, RunEnd.MISSED));

    /** The SLI's target: 99.99% of counted runs good. */
    public static final double TARGET = 0.9999;

    public static boolean isTerminal(JobStatus status) {
        return status != null && TERMINAL.contains(status);
    }

    /** @param reason null for a run that ended before reasons were recorded */
    public static RunSlo of(JobStatus status, RunEnd reason) {
        if (!isTerminal(status)) {
            throw new IllegalArgumentException("A run in " + status + " has not ended.");
        }
        if (status == JobStatus.Skip || status == JobStatus.Missed) {
            return EXCLUDED;
        }
        if (reason != null && EXCLUDED_REASONS.contains(reason)) {
            return EXCLUDED;
        }
        return status == JobStatus.Completed ? GOOD : BAD;
    }

    public String tag() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
