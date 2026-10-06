package process.model.enums;

import java.util.Locale;

/**
 * Why a run reached its terminal status: which code path closed it (MIG-196). Written to job_queue.end_reason with
 * the terminal status, and the {@code reason} tag of the process.runs.ended counter -- one small closed set, so the
 * tag stays low-cardinality and a stored row and a counted run are classified by the same rule
 * (process.slo.RunSlo). docs/SLO.md in etl-platform is the definition this serves.
 *
 * The column says why the run's CURRENT terminal status was written; null on a run still in flight, and on runs that
 * ended before V174 (the SLO report infers those from their status line).
 */
public enum RunEnd {

    /** Its worker reported Completed or Failed, after reporting Running (NotifyServiceImpl.changeState). */
    WORKER,
    /** Its worker reported Failed before ever reporting Running: it declined the run (MIG-201's Start -> Failed). */
    DECLINED,
    /** The dispatch side could not hand it to a worker and its retries ran out (DispatchFailures, retryable). */
    DISPATCH,
    /** The dispatch side refused it on its configuration: the job gone or off, no task, no route (not retryable). */
    REFUSED,
    /** A pipeline AI step failed before dispatch and its rule says the run fails (PreDispatchPhase). */
    AI_STEP,
    /** The stall sweep closed it: six hours with no word from its worker (C4: always Interrupt). */
    STALLED,
    /** The stall sweep closed it at once: its worker's report was refused for an expired token (MIG-63). */
    TOKEN_EXPIRED,
    /** A person closed it from the console: fail, interrupt, or a status set through /message.json/changeJobStatus. */
    OPERATOR,
    /** Written as Skip: skipped by a person, or because its job already had a run in flight. */
    SKIPPED,
    /** Written as Missed: a scheduled slot that passed while the platform was down. */
    MISSED;

    /** The counter's tag value: the name in lower case. */
    public String tag() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    /**
     * What a worker's own terminal report is: a decline when the run had not reported Running (its row still in
     * Queue or Start), otherwise the worker's outcome. Read from the RUN's status before the write, not the job's.
     */
    public static RunEnd reportedBy(JobStatus before, JobStatus reported) {
        boolean neverRan = before == JobStatus.Queue || before == JobStatus.Start;
        return reported == JobStatus.Failed && neverRan ? DECLINED : WORKER;
    }
}
