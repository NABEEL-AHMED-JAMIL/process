package process.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @author Nabeel Ahmed
 */
@Configuration
public class OutboxConfig {

    @Bean
    public OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactions,
        KafkaTemplate<String, String> kafka, OutboxWriter writer, @Value("${outbox.relay.poll-ms:1000}") long pollMillis) {
        OutboxRelay relay = new OutboxRelay(jdbc, new TransactionTemplate(transactions), kafka);
        relay.setPollMillis(pollMillis);
        writer.wakeOnCommit(relay::wake);
        return relay;
    }
}
