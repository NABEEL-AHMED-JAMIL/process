package process.customer;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The customer API's events out (MIG-333), declared by Core, their first producer, so the topic exists before
 * integration-service reads it: three partitions keyed by workspace (one workspace's events in order), the broker's
 * default (delete) retention -- an event is a fact delivered once, not a state.
 */
@Configuration
public class CustomerEventTopicConfig {

    static final int PARTITIONS = 3;

    @Bean
    public NewTopic customerEventsTopic(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        return new NewTopic(CustomerEventTypes.TOPIC, PARTITIONS, replication);
    }
}
