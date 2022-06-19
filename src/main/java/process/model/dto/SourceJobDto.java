package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceJobDto {

    private Long jobId;
    private String jobName;
    private TaskDetailDto taskDetail;
    private Status jobStatus;
    private JobStatus jobRunningStatus;
    private LocalDateTime lastJobRun;
    private Set<SchedulerDto> schedulers;
    private Timestamp dateCreated;

    public SourceJobDto() {}

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

    public TaskDetailDto getTaskDetail() {
        return taskDetail;
    }

    public void setTaskDetail(TaskDetailDto taskDetail) {
        this.taskDetail = taskDetail;
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

    public Set<SchedulerDto> getSchedulers() {
        return schedulers;
    }

    public void setSchedulers(Set<SchedulerDto> schedulers) {
        this.schedulers = schedulers;
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
