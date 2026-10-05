package process.identity;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * analytics.query.completed (event audit E6), declared by Core, which publishes it for Analytics (InternalKafkaPublishRestApi,
 * ADR-015): three partitions, keyed by workspace, the broker's (delete) retention -- a finished query is an event, not a
 * state. It was left to the broker's auto-create, one partition, and failed where auto-create is off (managed Kafka). This
 * declares it on the platform's brokers; a workspace with Kafka of its own makes its topics itself.
 */
@Configuration
public class AnalyticsTopicsConfig {

    static final String QUERY_COMPLETED = "analytics.query.completed";

    private static final int PARTITIONS = 3;

    @Bean
    public NewTopic analyticsQueryCompletedTopic(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        return TopicBuilder.name(QUERY_COMPLETED).partitions(PARTITIONS).replicas(replication).build();
    }
}
