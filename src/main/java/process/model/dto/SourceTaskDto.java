package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskDto {

    private Long taskDetailId;
    private String taskName;
    private Status taskStatus;

    private String taskHomePage;
    private String taskPayload;
    private SourceTaskTypeDto sourceTaskType;
    private List<ConfigurationMakerRequest.TagInfo> xmlTagsInfo;
    private Long totalLinksJobs;

    public SourceTaskDto() {}

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

    public String getTaskHomePage() {
        return taskHomePage;
    }

    public void setTaskHomePage(String taskHomePage) {
        this.taskHomePage = taskHomePage;
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

    public List<ConfigurationMakerRequest.TagInfo> getXmlTagsInfo() {
        return xmlTagsInfo;
    }

    public void setXmlTagsInfo(List<ConfigurationMakerRequest.TagInfo> xmlTagsInfo) {
        this.xmlTagsInfo = xmlTagsInfo;
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
