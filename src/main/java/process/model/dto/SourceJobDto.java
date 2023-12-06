package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceJobDto {

    private Long jobId;
    private String jobName;
    private SourceTaskDto taskDetail;
    private Status jobStatus;
    private JobStatus jobRunningStatus;
    private LocalDateTime lastJobRun;
    private Set<SchedulerDto> schedulers;
    private SchedulerDto scheduler;
    private Execution execution;
    private Integer priority;
    private Timestamp dateCreated;
    private List<Integer> jobIds;
    private boolean completeJob;
    private boolean failJob;
    private boolean skipJob;
    private String pdfReportUrl;
    private boolean tabActive;

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

    public SourceTaskDto getTaskDetail() {
        return taskDetail;
    }

    public void setTaskDetail(SourceTaskDto taskDetail) {
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

    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public SchedulerDto getScheduler() {
        return scheduler;
    }

    public void setScheduler(SchedulerDto scheduler) {
        this.scheduler = scheduler;
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

    public List<Integer> getJobIds() {
        return jobIds;
    }

    public void setJobIds(List<Integer> jobIds) {
        this.jobIds = jobIds;
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

    public String getPdfReportUrl() {
        return pdfReportUrl;
    }

    public void setPdfReportUrl(String pdfReportUrl) {
        this.pdfReportUrl = pdfReportUrl;
    }

    public boolean isTabActive() {
        return tabActive;
    }

    public void setTabActive(boolean tabActive) {
        this.tabActive = tabActive;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}