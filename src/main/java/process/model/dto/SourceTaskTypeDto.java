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
public class SourceTaskTypeDto {

    private Long sourceTaskTypeId;
    private String serviceName;
    private String description;
    private String queueTopicPartition;
    private Status status;
    private boolean isSchemaRegister;
    private String schemaPayload;

    public SourceTaskTypeDto() {
    }

    public Long getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(Long sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getQueueTopicPartition() {
        return queueTopicPartition;
    }

    public void setQueueTopicPartition(String queueTopicPartition) {
        this.queueTopicPartition = queueTopicPartition;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isSchemaRegister() {
        return isSchemaRegister;
    }

    public void setSchemaRegister(boolean schemaRegister) {
        isSchemaRegister = schemaRegister;
    }

    public String getSchemaPayload() {
        return schemaPayload;
    }

    public void setSchemaPayload(String schemaPayload) {
        this.schemaPayload = schemaPayload;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
