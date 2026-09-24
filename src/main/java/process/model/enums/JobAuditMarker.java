package process.model.enums;

/**
 * Audit lines whose exact wording other code depends on (MIG-77, DEF-156).
 *
 * A worker writes JOB_STARTED to job_audit_logs the moment it picks a run up, through the run callback;
 * QueryService.runReportRows finds the earliest one per run to separate execution time from the queue wait
 * in front of it, because job_queue.start_time is stamped at enqueue. The match is exact equality, and a
 * run without the line reports exec_seconds -1 and is left out of the execution figures -- so a worker that
 * rewords it zeroes the report's one useful contrast without an error anywhere. The worker is Python
 * (job-search, etl/tpd/tpd_scrapping_listener.py) and the report is here: this constant is the Java side of
 * that contract, and JobAuditMarkerContractTest holds the two sides together.
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
