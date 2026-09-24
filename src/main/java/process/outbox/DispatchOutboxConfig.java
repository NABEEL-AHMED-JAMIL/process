package process.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.engine.ProducerBulkEngine;

/**
 * Wires the dispatch outbox (MIG-136): the relay publishes what DispatchOutbox writes, is woken by it
 * after each commit, and reports each outcome back to the dispatcher.
 *
 * @author Nabeel Ahmed
 */
@Configuration
public class DispatchOutboxConfig {

    @Bean
    public DispatchRelay dispatchRelay(JdbcTemplate jdbc, PlatformTransactionManager transactions,
        KafkaConnectionResolver kafkaConnectionResolver, KafkaTemplateProvider kafkaTemplateProvider,
        DispatchOutbox writer, ProducerBulkEngine dispatcher, @Value("${dispatch.relay.poll-ms:1000}") long pollMillis) {
        DispatchRelay relay = new DispatchRelay(jdbc, new TransactionTemplate(transactions), kafkaConnectionResolver,
            kafkaTemplateProvider);
        relay.setPollMillis(pollMillis);
        relay.setOutcomes(dispatcher);
        writer.wakeOnCommit(relay::wake);
        return relay;
    }
}
