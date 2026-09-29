package process.inbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Storage's inbox topic (MIG-239), declared by its consumer so it exists before Storage's first arrival: three
 * partitions, keyed by workspace, the broker's default (delete) retention -- an arrival is an event, not a state.
 */
@Configuration
public class InboxTopicsConfig {

    private static final int PARTITIONS = 3;

    private final short replication;

    public InboxTopicsConfig(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        this.replication = replication;
    }

    @Bean
    public NewTopic inboxArrivedTopic() {
        return new NewTopic(InboxTopics.INBOX_ARRIVED, PARTITIONS, this.replication);
    }
}
