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

/**
 * Detail for job
 * this class store the detail for job
 * like
 * jobName => must be unique
 * triggerDetail => detail for class
 * jobRunningStatus => describe the current status of the job either (blank,queue,running,fail,complete) or etc..
 * jobStatus => describe the status of job (active or disable or delete)
 * lastJobRun => describe the status of last job run on date
 * nextJobRun => describe the status of next job run
 * if the no more execution exist then the status of the job still same as the old one => DONE
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_job", indexes = {
    // Every tenant-scoped query filters on this (see TenantFilterHelper's Hibernate
    // @Filter below) -- ddl-auto=update doesn't index plain @Column fields on its own, so
    // without this every one of those queries was a full table scan.
    @Index(name = "idx_source_job_tenant_id", columnList = "tenant_id"),
    // Forward-looking: whoever builds a "My Jobs"/reassignment UI will query by this.
    @Index(name = "idx_source_job_assigned_user_id", columnList = "assigned_user_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceJob {

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

    /** Owning tenant -- see Tenant/TenantContext. Nullable during the Phase 0 migration window
     * (backfilled to the Default tenant by TenantSeedService on startup). */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** Who this job's run notifications go to -- defaults to the creator on add (see
     * SourceJobServiceImpl.addSourceJob), reassignable via update. Resolved to a username for
     * the per-user WebSocket push in BulkAction.sendJobStatusNotification (see
     * SourceJobRepository.fetchRunningJobEvent's app_user join / SourceJobProjection.
     * getAssignedUsername). Nullable for jobs created before this field existed -- those just
     * don't get a live push (still visible via manual refresh/history), same as an assignee
     * with no active session. */
    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    // job name should be unique
    @Column(name = "job_name",
       length = 1000, nullable = false)
    private String jobName;

    // which class or method trigger
    @ManyToOne
    @JoinColumn(name = "task_detail_id")
    private SourceTask sourceTask;

    // status of job (active or disable or delete)
    @Column(name = "job_status",
         nullable = false)
    @Enumerated(EnumType.STRING)
    private Status jobStatus;

    // status :- blank,queue,running,fail,complete
    @Column(name = "job_running_status")
    @Enumerated(EnumType.STRING)
    private JobStatus jobRunningStatus;

    // describe the last job run
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

    public Long getAssignedUserId() {
        return assignedUserId;
    }

    public void setAssignedUserId(Long assignedUserId) {
        this.assignedUserId = assignedUserId;
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

}