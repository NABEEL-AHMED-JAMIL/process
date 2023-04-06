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
@Table(name = "kafka_task_type")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaTaskType {
    final static Integer REPLICATION_FACTOR = 1;

    @GenericGenerator(
        name = "kafkaTaskTypeSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "kafka_task_type_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="kafka_task_type_id", unique=true, nullable=false)
    @GeneratedValue(generator = "kafkaTaskTypeSequenceGenerator")
    private Long kafkaTaskTypeId;

    @Column(name = "topic_name", nullable = false)
    private String topicName;

    @Column(name = "num_partitions", nullable = false)
    private Integer numPartitions; // map 3

    @Column(name = "topic_pattern", nullable = false)
    private String topicPattern;

    @OneToOne
    @MapsId
    @JoinColumn(name = "source_task_type_id")
    private SourceTaskType sourceTaskType;

    @Column(name = "kafka_tt_status", nullable = false)
    @Enumerated(EnumType.ORDINAL)
    private Status status;

    public KafkaTaskType() {
    }

    public Long getKafkaTaskTypeId() {
        return kafkaTaskTypeId;
    }

    public void setKafkaTaskTypeId(Long kafkaTaskTypeId) {
        this.kafkaTaskTypeId = kafkaTaskTypeId;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public Integer getNumPartitions() {
        return numPartitions;
    }

    public void setNumPartitions(Integer numPartitions) {
        this.numPartitions = numPartitions;
    }

    public String getTopicPattern() {
        return topicPattern;
    }

    public void setTopicPattern(String topicPattern) {
        this.topicPattern = topicPattern;
    }

    public SourceTaskType getSourceTaskType() {
        return sourceTaskType;
    }

    public void setSourceTaskType(SourceTaskType sourceTaskType) {
        this.sourceTaskType = sourceTaskType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
