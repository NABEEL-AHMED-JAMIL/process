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
@Table(name = "source_task_type", indexes = {
    @Index(name = "idx_stt_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_stt_kafka_profile_id", columnList = "kafka_connection_profile_id")
})
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

    /** Owning tenant -- null means this is shared/global catalog data, reusable by every
     * tenant (the pre-existing convention: every row before this column existed stays null,
     * unchanged). A tenant may also own a private type of its own (tenant_id set). Kafka
     * routing for a shared (null-tenant) type can still be overridden per tenant without
     * duplicating this row -- see TenantTaskTypeKafkaRoute / KafkaConnectionResolver. */
    @Column(name = "tenant_id")
    private Long tenantId;

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

    /** This type's default Kafka cluster -- null falls through the rest of
     * KafkaConnectionResolver's chain (tenant default -> platform default -> env fallback). */
    @Column(name = "kafka_connection_profile_id")
    private Long kafkaConnectionProfileId;

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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
