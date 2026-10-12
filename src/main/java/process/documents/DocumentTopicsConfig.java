package process.documents;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The approval topic (MIG-364), declared by its consumer too -- as ai-service's OutboxConfig declares it, three
 * partitions keyed by workspace -- so Core's listener has a topic to join whichever service starts first.
 */
@Configuration
public class DocumentTopicsConfig {

    private static final int PARTITIONS = 3;

    private final short replication;

    public DocumentTopicsConfig(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        this.replication = replication;
    }

    @Bean
    public NewTopic documentApprovedTopic() {
        return new NewTopic(DocumentTopics.DOCUMENT_APPROVED, PARTITIONS, this.replication);
    }
}
