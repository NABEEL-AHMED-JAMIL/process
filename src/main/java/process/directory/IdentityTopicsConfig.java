package process.directory;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * The identity topics, declared COMPACTED (MIG-153): the broker keeps the latest state per id for ever, so a
 * service that starts consuming years later reads the topic from the beginning and has every workspace and
 * person. The broker's auto-create would make them delete-policy, and the directory would evaporate after
 * the retention.
 */
@Configuration
public class IdentityTopicsConfig {

    private static final int PARTITIONS = 3;

    private final short replication;

    public IdentityTopicsConfig(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        this.replication = replication;
    }

    @Bean
    public NewTopic identityUserTopic() {
        return compacted(IdentityTopics.USER);
    }

    @Bean
    public NewTopic identityTenantTopic() {
        return compacted(IdentityTopics.TENANT);
    }

    private NewTopic compacted(String name) {
        return new NewTopic(name, PARTITIONS, this.replication).configs(Collections.singletonMap("cleanup.policy", "compact"));
    }
}
