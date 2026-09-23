package process.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.pojo.Audited;
import process.model.projection.PipelineRowProjection;
import java.time.LocalDateTime;

/** A list row for the Pipelines screen: see {@link PipelineRowProjection}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineRowDto implements Audited {
    private Long pipelineKey;
    private String pipelineId;
    private String pipelineName;
    private String description;
    private Long tenantId;
    private Long sourceTaskTypeId;
    private String topicName;
    private String kafkaTopic;
    /** The Kafka profile the topic lives on, so a link to it can open the right one. */
    private Long kafkaConnectionProfileId;
    private String status;
    private LocalDateTime dateCreated;
    private Long createdBy;
    private String createdByName;
    private Long updatedBy;
    private String updatedByName;
    private long fieldCount;
    private long requiredCount;

    public static PipelineRowDto from(PipelineRowProjection row) {
        PipelineRowDto dto = new PipelineRowDto();
        dto.pipelineKey = row.getPipelineKey();
        dto.pipelineId = row.getPipelineId();
        dto.pipelineName = row.getPipelineName();
        dto.description = row.getDescription();
        dto.tenantId = row.getTenantId();
        dto.sourceTaskTypeId = row.getSourceTaskTypeId();
        dto.status = row.getStatus();
        dto.dateCreated = row.getDateCreated();
        dto.createdBy = row.getCreatedBy();
        dto.updatedBy = row.getUpdatedBy();
        dto.fieldCount = row.getFieldCount() == null ? 0 : row.getFieldCount();
        dto.requiredCount = row.getRequiredCount() == null ? 0 : row.getRequiredCount();
        return dto;
    }

    public Long getPipelineKey() { return pipelineKey; }
    public String getPipelineId() { return pipelineId; }
    public String getPipelineName() { return pipelineName; }
    public String getDescription() { return description; }
    public Long getTenantId() { return tenantId; }
    public Long getSourceTaskTypeId() { return sourceTaskTypeId; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }
    public String getKafkaTopic() { return kafkaTopic; }
    public void setKafkaTopic(String kafkaTopic) { this.kafkaTopic = kafkaTopic; }
    public Long getKafkaConnectionProfileId() { return kafkaConnectionProfileId; }
    public void setKafkaConnectionProfileId(Long kafkaConnectionProfileId) { this.kafkaConnectionProfileId = kafkaConnectionProfileId; }
    public String getStatus() { return status; }
    public LocalDateTime getDateCreated() { return dateCreated; }
    public long getFieldCount() { return fieldCount; }
    public long getRequiredCount() { return requiredCount; }

    @Override public Long getCreatedBy() { return createdBy; }
    @Override public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    @Override public Long getUpdatedBy() { return updatedBy; }
    @Override public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    @Override public String getCreatedByName() { return createdByName; }
    @Override public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    @Override public String getUpdatedByName() { return updatedByName; }
    @Override public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}
