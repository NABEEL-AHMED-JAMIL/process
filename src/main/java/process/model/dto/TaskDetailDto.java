package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskDetailDto {

    private Long taskDetailId;
    private String taskName;
    private Status taskStatus;
    private String taskPayload;
    private SourceTaskTypeDto sourceTaskType;
    private Long totalLinksJobs;

    public TaskDetailDto() {}

    public Long getTaskDetailId() {
        return taskDetailId;
    }

    public void setTaskDetailId(Long taskDetailId) {
        this.taskDetailId = taskDetailId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Status getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(Status taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getTaskPayload() {
        return taskPayload;
    }

    public void setTaskPayload(String taskPayload) {
        this.taskPayload = taskPayload;
    }

    public SourceTaskTypeDto getSourceTaskType() {
        return sourceTaskType;
    }

    public void setSourceTaskType(SourceTaskTypeDto sourceTaskType) {
        this.sourceTaskType = sourceTaskType;
    }

    public Long getTotalLinksJobs() {
        return totalLinksJobs;
    }

    public void setTotalLinksJobs(Long totalLinksJobs) {
        this.totalLinksJobs = totalLinksJobs;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
