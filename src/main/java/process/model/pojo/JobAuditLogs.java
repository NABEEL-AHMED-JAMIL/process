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
    @Column(name = "job_audit_log_id")
    @GeneratedValue(generator = "jobAuditLogsSequenceGenerator")
    private Long jobAuditLogId;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @ManyToOne
    @JoinColumn(name = "job_queue_id", nullable = false)
    private JobQueue jobQueue;

    @Column(name = "log_detail",
        nullable = false, length = 2500)
    private String logsDetail;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    public JobAuditLogs() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getJobAuditLogId() {
        return jobAuditLogId;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public void setJobAuditLogId(Long jobAuditLogId) {
        this.jobAuditLogId = jobAuditLogId;
    }

    public JobQueue getJobQueue() {
        return jobQueue;
    }

    public void setJobQueue(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
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