package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobAuditLogsDto {

    private Long jobAuditLogId;

    private Long jobId;

    private Long jobHistoryId;

    private String logsDetail;

    private Timestamp dateCreated;

    public JobAuditLogsDto() {}

    public Long getJobAuditLogId() {
        return jobAuditLogId;
    }
    public void setJobAuditLogId(Long jobAuditLogId) {
        this.jobAuditLogId = jobAuditLogId;
    }

    public Long getJobId() {
        return jobId;
    }
    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Long getJobHistoryId() {
        return jobHistoryId;
    }
    public void setJobHistoryId(Long jobHistoryId) {
        this.jobHistoryId = jobHistoryId;
    }

    public String getLogsDetail() {
        return logsDetail;
    }
    public void setLogsDetail(String logsDetail) {
        this.logsDetail = logsDetail;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }
    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
