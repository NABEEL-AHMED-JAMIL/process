package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
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
@Table(name = "job")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Job {

    @GenericGenerator(
        name = "jobSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "job_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @GeneratedValue(generator = "jobSequenceGenerator")
    private Long jobId;

    // job name should be unique
    @Column(length = 1000, nullable = false)
    private String jobName;

    // which class or method trigger
    @Column(length = 1000, nullable = false)
    private String triggerDetail;

    // status of job (active or disable or delete)
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status jobStatus;

    // status :- blank,queue,running,fail,complete
    @Enumerated(EnumType.STRING)
    private JobStatus jobRunningStatus;

    // describe the last job run
    @Column(columnDefinition = "TIMESTAMP")
    private LocalDateTime lastJobRun;

    @Column(nullable = false)
    private Timestamp dateCreated;

    public Job() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getJobId() {
        return jobId;
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

    public String getTriggerDetail() {
        return triggerDetail;
    }
    public void setTriggerDetail(String triggerDetail) {
        this.triggerDetail = triggerDetail;
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