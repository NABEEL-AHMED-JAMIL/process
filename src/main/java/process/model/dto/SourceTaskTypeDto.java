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
    private Long tenantId;
    private String serviceName;
    private String description;
    private String queueTopicPartition;
    private Status status;
    /** This type's default Kafka cluster -- null means "fall through to the tenant's own
     * default" (see KafkaConnectionResolver). Settable by the caller. */
    private Long kafkaConnectionProfileId;
    /** Set on read only, for display -- the resolved profile's name (never sent back on write). */
    private String kafkaConnectionProfileName;
    /** Set on read only (appSetting) -- how many SourceTasks currently link to this type. */
    private Long totalTaskLink;

    public SourceTaskTypeDto() {
    }

    public Long getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(Long sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
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

    public Long getKafkaConnectionProfileId() {
        return kafkaConnectionProfileId;
    }

    public void setKafkaConnectionProfileId(Long kafkaConnectionProfileId) {
        this.kafkaConnectionProfileId = kafkaConnectionProfileId;
    }

    public String getKafkaConnectionProfileName() {
        return kafkaConnectionProfileName;
    }

    public void setKafkaConnectionProfileName(String kafkaConnectionProfileName) {
        this.kafkaConnectionProfileName = kafkaConnectionProfileName;
    }

    public Long getTotalTaskLink() {
        return totalTaskLink;
    }

    public void setTotalTaskLink(Long totalTaskLink) {
        this.totalTaskLink = totalTaskLink;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
