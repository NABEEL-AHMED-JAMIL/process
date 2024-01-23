package process.model.projection;

/**
 * @author Nabeel Ahmed
 */
public interface JobAuditLogProjection {

    public Long getJobAuditLogId();

    public Long getJobQueueId();

    public String getLogsDetail();

}