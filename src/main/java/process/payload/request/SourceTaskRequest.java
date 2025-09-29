package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskRequest {

    private Long taskDetailId;
    private Long sttId;
    private String taskName;
    private Long taskStatus;
    private FormRequest formRequest;

    public SourceTaskRequest() {
    }

    public Long getTaskDetailId() {
        return taskDetailId;
    }

    public void setTaskDetailId(Long taskDetailId) {
        this.taskDetailId = taskDetailId;
    }

    public Long getSttId() {
        return sttId;
    }

    public void setSttId(Long sttId) {
        this.sttId = sttId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Long getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(Long taskStatus) {
        this.taskStatus = taskStatus;
    }

    public FormRequest getFormRequest() {
        return formRequest;
    }

    public void setFormRequest(FormRequest formRequest) {
        this.formRequest = formRequest;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
