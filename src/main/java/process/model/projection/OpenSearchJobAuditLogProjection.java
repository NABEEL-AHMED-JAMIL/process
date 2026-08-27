package process.model.projection;

import java.util.UUID;

/**
 * @author Nabeel Ahmed
 * */
public class OpenSearchJobAuditLogProjection implements JobAuditLogProjection {

    private final String externalId;
    private final Long jobQueueId;
    private final String logsDetail;
    private final String dateCreated;

    public OpenSearchJobAuditLogProjection(String externalId, Long jobQueueId, String logsDetail, String dateCreated) {
        this.externalId = externalId;
        this.jobQueueId = jobQueueId;
        this.logsDetail = logsDetail;
        this.dateCreated = dateCreated;
    }

    @Override
    public Long getJobAuditLogId() {
        try {
            return UUID.fromString(this.externalId).getLeastSignificantBits() & Long.MAX_VALUE;
        } catch (IllegalArgumentException ex) {
            return ((long) this.externalId.hashCode()) & Integer.MAX_VALUE;
        }
    }

    @Override
    public Long getJobQueueId() {
        return this.jobQueueId;
    }

    @Override
    public String getLogsDetail() {
        return this.logsDetail;
    }

    @Override
    public String getDateCreated() {
        return this.dateCreated;
    }

    @Override
    public String getStatus() {
        return "Active";
    }

    public String getExternalId() {
        return this.externalId;
    }

}
