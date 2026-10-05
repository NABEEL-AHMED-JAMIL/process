package process.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
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

    /**
     * The parked events (event audit E2): /actuator/health's "outbox" detail and the platform.outbox.dead gauge. Always UP: a
     * parked event is for someone to look at, not a reason to restart the service.
     */
    @Bean
    public HealthIndicator outboxHealthIndicator(OutboxRelay relay) {
        return () -> Health.up().withDetail("deadEvents", relay.deadCount()).build();
    }

    /** Read on each scrape through the provider: the relay needs the producer, whose metrics need the registry. */
    @Bean
    public MeterBinder outboxDeadGauge(ObjectProvider<OutboxRelay> relay) {
        return registry -> Gauge.builder("platform.outbox.dead", relay, r -> r.getObject().deadCount())
            .description("Outbox events parked as dead: never sent, and holding nothing up").register(registry);
    }
}
