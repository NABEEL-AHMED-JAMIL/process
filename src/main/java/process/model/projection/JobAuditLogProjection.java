package process.model.projection;

/**
 * @author Nabeel Ahmed
 * */
public interface JobAuditLogProjection {

    public Long getJobAuditLogId();

    public Long getJobQueueId();

    public String getLogsDetail();

    public String getDateCreated();

    public String getStatus();

    public String getExternalId();

    /** The correlation id of the work that wrote the line (MIG-94): one string joins the trail and every log. */
    public String getCorrelationId();

}
