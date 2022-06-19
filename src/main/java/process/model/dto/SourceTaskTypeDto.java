package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskTypeDto {

    private String sourceTaskTypeId;
    private String serviceName;
    private String description;
    private String queueTopicPartition;

    public SourceTaskTypeDto() {
    }

    public String getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(String sourceTaskTypeId) {
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
