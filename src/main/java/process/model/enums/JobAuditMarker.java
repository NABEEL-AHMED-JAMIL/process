package process.model.enums;

/**
 * Audit lines whose exact wording other code depends on (MIG-77, DEF-156).
 *
 * A worker writes JOB_STARTED to the run's audit log the moment it picks a run up: it is the message of the
 * worker's Running callback, which NotifyServiceImpl.changeState writes as it accepts the state.
 * QueryService.runReportRows finds the earliest one per run to separate execution time from the queue wait
 * in front of it, because job_queue.start_time is stamped at enqueue. The match is exact equality, and a
 * run without the line reports exec_seconds -1 and is left out of the execution figures -- so a worker that
 * rewords it zeroes the report's one useful contrast without an error anywhere. The worker is service-1's
 * runtime (com.barco.service1.runtime.core.JobAuditMarker.JOB_STARTED, WORKER-CONTRACT.md section 4.1) and
 * the report is here: this constant is Core's side of that contract, JobAuditMarkerContractTest holds the two
 * sides together, and RunStartMarkerMonitor shows how many finished runs are missing the line.
 */
public enum JobAuditMarker {

    JOB_STARTED("Job started");

    private final String logDetail;

    JobAuditMarker(String logDetail) {
        this.logDetail = logDetail;
    }

    /** The log_detail text, exactly as the worker writes it. */
    public String logDetail() {
        return this.logDetail;
    }
}
