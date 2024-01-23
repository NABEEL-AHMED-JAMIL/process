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

    @Column(name = "skip_time",
        columnDefinition = "TIMESTAMP")
    private LocalDateTime skipTime;

    @Column(name = "job_status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private JobStatus jobStatus;

    @ManyToOne
    @JoinColumn(name = "job_id",
        nullable = false)
    private SourceJob sourceJob;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @Column(name = "job_status_message", length = 2500)
    private String jobStatusMessage;

    @Column(name = "skip_manual")
    private Boolean skipManual;

    @Column(name = "run_manual")
    private Boolean runManual;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    @Column(name = "job_send")
    private boolean jobSend;

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

    public LocalDateTime getSkipTime() {
        return skipTime;
    }

    public void setSkipTime(LocalDateTime skipTime) {
        this.skipTime = skipTime;
    }

    public JobStatus getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(JobStatus jobStatus) {
        this.jobStatus = jobStatus;
    }

    public String getJobStatusMessage() {
        return jobStatusMessage;
    }

    public void setJobStatusMessage(String jobStatusMessage) {
        this.jobStatusMessage = jobStatusMessage;
    }

    public Boolean getSkipManual() {
        return skipManual;
    }

    public void setSkipManual(Boolean skipManual) {
        this.skipManual = skipManual;
    }

    public Boolean getRunManual() {
        return runManual;
    }

    public void setRunManual(Boolean runManual) {
        this.runManual = runManual;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public boolean isJobSend() {
        return jobSend;
    }

    public void setJobSend(boolean jobSend) {
        this.jobSend = jobSend;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}