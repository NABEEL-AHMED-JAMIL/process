package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceJobQueueDto {

    private Long jobQueueId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime skipTime;
    private JobStatus jobStatus;
    private Long jobId;
    private boolean jobSend;
    private String jobName;
    private String jobStatusMessage;
    private Boolean skipManual;
    private Boolean runManual;
    private Timestamp dateCreated;
    private List<JobStatusStatisticDto> jobStatusStatistic;

    public SourceJobQueueDto() {}

    /** Builds the minimal DTO used for email-notification templates -- was previously
     * duplicated identically in ProducerBulkEngine and MessageQServiceImpl. startTime resolves
     * to skipTime when the job was skipped, since that's the timestamp the email should show. */
    public static SourceJobQueueDto forEmailNotification(JobQueue jobQueue) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(jobQueue.getJobId());
        dto.setJobQueueId(jobQueue.getJobQueueId());
        dto.setStartTime(jobQueue.getJobStatus().equals(JobStatus.Skip) ? jobQueue.getSkipTime() : jobQueue.getStartTime());
        return dto;
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

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public boolean isJobSend() {
        return jobSend;
    }

    public void setJobSend(boolean jobSend) {
        this.jobSend = jobSend;
    }

    public Boolean getSkipManual() {
        return skipManual;
    }

    public Boolean getRunManual() {
        return runManual;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobStatusMessage() {
        return jobStatusMessage;
    }

    public void setJobStatusMessage(String jobStatusMessage) {
        this.jobStatusMessage = jobStatusMessage;
    }

    public Boolean isSkipManual() {
        return skipManual;
    }

    public void setSkipManual(Boolean skipManual) {
        this.skipManual = skipManual;
    }

    public Boolean isRunManual() {
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

    public List<JobStatusStatisticDto> getJobStatusStatistic() {
        return jobStatusStatistic;
    }

    public void setJobStatusStatistic(List<JobStatusStatisticDto> jobStatusStatistic) {
        this.jobStatusStatistic = jobStatusStatistic;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
