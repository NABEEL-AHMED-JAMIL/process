package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_task_type")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskType {

    @GenericGenerator(
        name = "sourceTaskTypeSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "source_task_type_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="source_task_type_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sourceTaskTypeSequenceGenerator")
    private Long sourceTaskTypeId;

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

    // status of job (active or disable or delete)
    @Column(name = "task_type_status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "is_schema_register")
    private boolean isSchemaRegister;

    @Column(name = "schema_payload",
        columnDefinition = "text")
    private String schemaPayload;

    @Column(name = "user_id")
    private Long userId;

    public SourceTaskType() {}

    public SourceTaskType(Long sourceTaskTypeId, String queueTopicPartition) {
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.queueTopicPartition = queueTopicPartition;
    }

    public SourceTaskType(Long sourceTaskTypeId, String serviceName,
        String description, String queueTopicPartition) {
        this.sourceTaskTypeId = sourceTaskTypeId;
        this.serviceName = serviceName;
        this.description = description;
        this.queueTopicPartition = queueTopicPartition;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
