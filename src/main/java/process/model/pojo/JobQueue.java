package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.JobStatus;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Detail for job-queue
 * this class store the detail for job-queue
 * like
 * startTime => start time of the job
 * endTime => end time of the job
 * job-status => job status for job
 * job-status-message => job status message for job
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "job_queue")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobQueue {

    @GenericGenerator(
        name = "jobQueueSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "job_queue_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "job_queue_id")
    @GeneratedValue(generator = "jobQueueSequenceGenerator")
    private Long jobQueueId;

    @Column(name = "start_time",
        columnDefinition = "TIMESTAMP")
    private LocalDateTime startTime;

    @Column(name = "end_time",
        columnDefinition = "TIMESTAMP")
    private LocalDateTime endTime;

    @Column(name = "job_status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private JobStatus jobStatus;

    @Column(name = "job_id",
        nullable = false)
    private Long jobId;

    @Column(name = "job_status_message")
    private String jobStatusMessage;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    public JobQueue() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getJobQueueId() {
        return jobQueueId;
    }

    public void setJobQueueId(Long jobQueueId) {
        this.jobQueueId = jobQueueId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }
    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public JobStatus getJobStatus() {
        return jobStatus;
    }
    public void setJobStatus(JobStatus jobStatus) {
        this.jobStatus = jobStatus;
    }

    public Long getJobId() {
        return jobId;
    }
    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobStatusMessage() {
        return jobStatusMessage;
    }
    public void setJobStatusMessage(String jobStatusMessage) {
        this.jobStatusMessage = jobStatusMessage;
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