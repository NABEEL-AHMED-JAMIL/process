package process.notifications;

import org.apache.kafka.clients.admin.NewTopic;
import org.barco.notifications.contract.NotificationTopics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The five contract topics, declared rather than left to the broker's auto-create, which would make
 * each with a single partition. Keys give per-run and per-recipient order (see the contract), so
 * partitions only add parallelism across runs.
 *
 * @author Nabeel Ahmed
 */
@Configuration
public class NotificationTopicsConfig {

    private static final int PARTITIONS = 3;

    @Value("${kafka.topic.default-replication-factor:1}")
    private short replication;

    @Bean
    public NewTopic jobStatusTopic() {
        return new NewTopic(NotificationTopics.JOB_STATUS, PARTITIONS, this.replication);
    }

    @Bean
    public NewTopic jobLogTopic() {
        return new NewTopic(NotificationTopics.JOB_LOG, PARTITIONS, this.replication);
    }

    @Bean
    public NewTopic jobLifecycleTopic() {
        return new NewTopic(NotificationTopics.JOB_LIFECYCLE, PARTITIONS, this.replication);
    }

    @Bean
    public NewTopic notificationCreatedTopic() {
        return new NewTopic(NotificationTopics.NOTIFICATION_CREATED, PARTITIONS, this.replication);
    }

    @Bean
    public NewTopic mailRequestedTopic() {
        return new NewTopic(NotificationTopics.MAIL_REQUESTED, PARTITIONS, this.replication);
    }
}
