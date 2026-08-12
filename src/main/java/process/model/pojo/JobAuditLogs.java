package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import process.model.enums.Status;

/**
 * Detail for job-audit-logs
 * this class store the detail for job
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
// job_queue_id is how every audit-log lookup for a specific run is filtered (JobAuditLogRepository
// findByJobQueueId, and the bulk status-update query joining through job_queue.job_id) but had no
// supporting index -- just the primary key. Same reasoning as JobQueue's own index above: this
// table has no archival job either, so it's the more consequential of the two to fix now.
@Table(name = "job_audit_logs", indexes = {
    @Index(name = "idx_job_audit_logs_job_queue_id", columnList = "job_queue_id")
})
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
    @Column(name = "job_audit_log_id")
    @GeneratedValue(generator = "jobAuditLogsSequenceGenerator")
    private Long jobAuditLogId;

    @Column(name = "job_queue_id",
        nullable = false)
    private Long jobQueueId;

    @Column(name = "log_detail", nullable = false, columnDefinition = "TEXT")
    private String logsDetail;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    @Column(name = "status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    public JobAuditLogs() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
        if (this.status == null) {
            this.status = Status.Active;
        }
    }

    public Long getJobAuditLogId() {
        return jobAuditLogId;
    }

    public void setJobAuditLogId(Long jobAuditLogId) {
        this.jobAuditLogId = jobAuditLogId;
    }

    public Long getJobQueueId() {
        return jobQueueId;
    }

    public void setJobQueueId(Long jobQueueId) {
        this.jobQueueId = jobQueueId;
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

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}