package process.model.projection;

public interface JobAuditLogProjection {

    public Long getJobAuditLogId();

    public Long getJobQueueId();

    public String getLogsDetail();

    public String getDateCreated();

    public String getStatus();

    public String getExternalId();

}
