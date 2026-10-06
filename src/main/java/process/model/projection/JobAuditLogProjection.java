package process.model.projection;

/**
 * @author Nabeel Ahmed
 * */
public interface JobAuditLogProjection {

    Long getJobAuditLogId();

    Long getJobQueueId();

    String getLogsDetail();

    String getDateCreated();

    String getStatus();

    String getExternalId();

    /** The correlation id of the work that wrote the line (MIG-94): one string joins the trail and every log. */
    String getCorrelationId();

}
