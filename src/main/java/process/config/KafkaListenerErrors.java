package process.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.barco.platform.identity.IdentityTopics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import process.inbox.InboxTopics;

/**
 * What a listener does with a record it cannot handle (event audit E4). Spring Kafka's default was ten tries with no pause
 * and then the record skipped -- an inbox arrival
 * that started no pipeline, a form approval that never landed, a workspace deletion not acted on. Now a passing failure (the database, the network) is tried
 * again after 1, 2, 4 ... seconds, at most 30 apart, ten tries in all (about two and a half minutes, inside the
 * consumer's five-minute poll interval); one that is still failing then, or one no retry can fix (an unreadable event),
 * goes to "<topic>.DLT" with the reason in its headers, and the partition moves on. The DLT topics are declared here.
 */
@Configuration
public class KafkaListenerErrors {

    static final String DLT = ".DLT";

    /** Workflow's topic (FormWorkflowListener reads it for form approvals). */
    static final String WORKFLOW_INSTANCE_CHANGED = "platform.workflow.instance-changed.v1";

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
            (record, failure) -> deadLetterOf(record.topic()));
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backOff());
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

    /** 1, 2, 4, 8, 16, 30, 30, 30, 30 seconds between the ten tries. */
    static ExponentialBackOffWithMaxRetries backOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(9);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);
        return backOff;
    }

    /** The topic's dead letters, partition left to the producer (the DLT has one; the topic may have more). */
    static TopicPartition deadLetterOf(String topic) {
        return new TopicPartition(topic + DLT, -1);
    }

    @Bean
    public NewTopic identityUserDeadLetters(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        return deadLetters(IdentityTopics.USER, replication);
    }

    @Bean
    public NewTopic identityTenantDeadLetters(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        return deadLetters(IdentityTopics.TENANT, replication);
    }

    @Bean
    public NewTopic inboxArrivedDeadLetters(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        return deadLetters(InboxTopics.INBOX_ARRIVED, replication);
    }

    @Bean
    public NewTopic workflowInstanceDeadLetters(@Value("${kafka.topic.default-replication-factor:1}") short replication) {
        return deadLetters(WORKFLOW_INSTANCE_CHANGED, replication);
    }

    private static NewTopic deadLetters(String topic, short replication) {
        return TopicBuilder.name(topic + DLT).partitions(1).replicas(replication).build();
    }
}
