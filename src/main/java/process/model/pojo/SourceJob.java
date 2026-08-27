package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "source_job", indexes = {

    @Index(name = "idx_source_job_tenant_id", columnList = "tenant_id"),

    @Index(name = "idx_source_job_assigned_user_id", columnList = "assigned_user_id")
})
/**
 * @author Nabeel Ahmed
 * */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class SourceJob implements Audited {
    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;


    @GenericGenerator(
        name = "sourceJobSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "source_job_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "job_id")
    @GeneratedValue(generator = "sourceJobSequenceGenerator")
    private Long jobId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_user_id", insertable = false, updatable = false)
    private AppUser assignedUser;

    @Column(name = "job_name",
       length = 1000, nullable = false)
    private String jobName;

    @ManyToOne
    @JoinColumn(name = "task_detail_id")
    private SourceTask sourceTask;

    @Column(name = "job_status",
         nullable = false)
    @Enumerated(EnumType.STRING)
    private Status jobStatus;

    @Column(name = "job_running_status")
    @Enumerated(EnumType.STRING)
    private JobStatus jobRunningStatus;

    @Column(name = "last_job_run",
         columnDefinition = "TIMESTAMP")
    private LocalDateTime lastJobRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution",
        nullable = false)
    private Execution execution;

    @Column(name = "priority",
        nullable = false)
    private Integer priority;

    @Column(name = "date_created",
         nullable = false)
    private Timestamp dateCreated;

    @Column(name = "complete_job")
    private boolean completeJob;

    @Column(name = "fail_job")
    private boolean failJob;

    @Column(name = "skip_job")
    private boolean skipJob;

    public SourceJob() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getJobId() {
        return jobId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public Long getAssignedUserId() {
        return assignedUserId;
    }

    public void setAssignedUserId(Long assignedUserId) {
        this.assignedUserId = assignedUserId;
    }

    public AppUser getAssignedUser() {
        return assignedUser;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public SourceTask getTaskDetail() {
        return sourceTask;
    }

    public void setTaskDetail(SourceTask sourceTask) {
        this.sourceTask = sourceTask;
    }

    public Status getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(Status jobStatus) {
        this.jobStatus = jobStatus;
    }

    public JobStatus getJobRunningStatus() {
        return jobRunningStatus;
    }

    public void setJobRunningStatus(JobStatus jobRunningStatus) {
        this.jobRunningStatus = jobRunningStatus;
    }

    public LocalDateTime getLastJobRun() {
        return lastJobRun;
    }

    public void setLastJobRun(LocalDateTime lastJobRun) {
        this.lastJobRun = lastJobRun;
    }

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public boolean isCompleteJob() {
        return completeJob;
    }

    public void setCompleteJob(boolean completeJob) {
        this.completeJob = completeJob;
    }

    public boolean isFailJob() {
        return failJob;
    }

    public void setFailJob(boolean failJob) {
        this.failJob = failJob;
    }

    public boolean isSkipJob() {
        return skipJob;
    }

    public void setSkipJob(boolean skipJob) {
        this.skipJob = skipJob;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }


    @Override
    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public Long getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    @Override
    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }
}