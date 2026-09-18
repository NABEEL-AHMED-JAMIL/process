package process.model.projection;

/**
 * A topic as a picker needs it -- id, name, Kafka topic, state, which profile and workspace --
 * and nothing else. appSetting describes every topic in full (description, task counts, profile
 * names, author names); a combobox over ten thousand of them only needs these six columns.
 */
public interface TopicOptionProjection {
    Long getSourceTaskTypeId();
    String getServiceName();
    String getQueueTopicPartition();
    String getStatus();
    Long getKafkaConnectionProfileId();
    Long getTenantId();
}
