package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import process.model.enums.TaskType;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTRequest {

    private Long sttId;
    private String description; // yes
    private String serviceName; // yes
    private Status status;
    private TaskType taskType; // yes
    private ParseRequest accessUserDetail; // yes
    private boolean isDefault;
    private KafkaTaskTypeRequest kafkaTaskType; // base on task type
    private ApiTaskTypeRequest apiTaskType; // base on task type

    public STTRequest() {
    }

    public Long getSttId() {
        return sttId;
    }

    public void setSttId(Long sttId) {
        this.sttId = sttId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public ParseRequest getAccessUserDetail() {
        return accessUserDetail;
    }

    public void setAccessUserDetail(ParseRequest accessUserDetail) {
        this.accessUserDetail = accessUserDetail;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public KafkaTaskTypeRequest getKafkaTaskType() {
        return kafkaTaskType;
    }

    public void setKafkaTaskType(KafkaTaskTypeRequest kafkaTaskType) {
        this.kafkaTaskType = kafkaTaskType;
    }

    public ApiTaskTypeRequest getApiTaskType() {
        return apiTaskType;
    }

    public void setApiTaskType(ApiTaskTypeRequest apiTaskType) {
        this.apiTaskType = apiTaskType;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}