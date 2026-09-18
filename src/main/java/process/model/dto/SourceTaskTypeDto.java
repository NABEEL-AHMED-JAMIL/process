package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskTypeDto implements AuditNamed {
    private Long createdBy;

    private String createdByName;
    private String updatedByName;


    private Long sourceTaskTypeId;
    private Long tenantId;
    private String serviceName;
    private String description;
    private String queueTopicPartition;
    private Status status;

    private Long kafkaConnectionProfileId;

    private String kafkaConnectionProfileName;

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

    @Override
    public Long auditKey() {
        return sourceTaskTypeId;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    /** One line per pipeline on the topic -- what a profile's Topics table shows in the row. */
    public static class PipelineSummary {
        private Long pipelineKey;
        private String pipelineId;
        private String pipelineName;
        private String status;
        private int fields;
        public PipelineSummary() { }
        public PipelineSummary(Long pipelineKey, String pipelineId, String pipelineName, String status, int fields) {
            this.pipelineKey = pipelineKey; this.pipelineId = pipelineId; this.pipelineName = pipelineName; this.status = status; this.fields = fields;
        }
        public Long getPipelineKey() { return pipelineKey; }
        public String getPipelineId() { return pipelineId; }
        public String getPipelineName() { return pipelineName; }
        public String getStatus() { return status; }
        public int getFields() { return fields; }
    }

    private java.util.List<PipelineSummary> pipelines;
    public java.util.List<PipelineSummary> getPipelines() { return pipelines; }
    public void setPipelines(java.util.List<PipelineSummary> pipelines) { this.pipelines = pipelines; }
}
