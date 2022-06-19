package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_task_type")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskType {

    @Id
    @Column(name="source_task_type_id",
        unique=true, nullable=false)
    private String sourceTaskTypeId;

    @Column(name = "service_name",
        nullable = false)
    private String serviceName;

    @Column(name = "description",
        nullable = false)
    private String description;

    /**
     * filed help to send the source job to the right queue
     * */
    @Column(name = "queue_topic_partition",
        nullable = false)
    private String queueTopicPartition;

    public SourceTaskType() {}

    public SourceTaskType(String sourceTaskTypeId, String queueTopicPartition) {
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.queueTopicPartition = queueTopicPartition;
    }

    public SourceTaskType(String sourceTaskTypeId, String serviceName,
                          String description, String queueTopicPartition) {
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.serviceName = serviceName;
        this.description = description;
        this.queueTopicPartition = queueTopicPartition;
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
