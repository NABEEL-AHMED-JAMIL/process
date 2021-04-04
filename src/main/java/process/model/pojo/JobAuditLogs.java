package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * Detail for job-audit-logs
 * this class store the detail for job
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "job_audit_logs")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobAuditLogs {

    @GenericGenerator(
        name = "jobAuditLogsSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "job_audit_logs_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @GeneratedValue(generator = "jobAuditLogsSequenceGenerator")
    private Long jobAuditLogId;

    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false)
    private Long jobHistoryId;

    @Column(nullable = false)
    private String logsDetail;

    @Column(nullable = false)
    private Timestamp dateCreated;

    public JobAuditLogs() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

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