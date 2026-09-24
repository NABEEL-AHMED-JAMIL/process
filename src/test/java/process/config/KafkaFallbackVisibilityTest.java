package process.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-45 (DEF-127), criterion 4: a dispatch that no profile resolved for goes out on the auto-configured
 * fallback template. That stays -- it is the deliberate degrade-never-fail path -- but it is no longer
 * silent: every use is counted and logged, so a tenant's payloads landing on the shared brokers shows up.
 */
class KafkaFallbackVisibilityTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> fallback = mock(KafkaTemplate.class);
    private final KafkaTemplateProvider provider = new KafkaTemplateProvider(null, this.fallback, null, null);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KafkaTemplateProvider.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void capture() {
        this.provider.setMeterRegistry(this.meters);
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void release() {
        this.logger.detachAppender(this.log);
    }

    @Test
    void everyFallbackUseIsCountedAndLogged() {
        assertThat(this.provider.getTemplate(Optional.empty())).isSameAs(this.fallback);
        assertThat(this.provider.getTemplate(Optional.empty())).isSameAs(this.fallback);

        assertThat(this.meters.counter(KafkaTemplateProvider.FALLBACK_METER).count()).isEqualTo(2.0);
        assertThat(this.log.list).filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage).hasSize(2)
            .allSatisfy(line -> assertThat(line).contains("no Kafka connection profile resolved"));
    }

    @Test
    void withoutARegistryTheFallbackIsStillLogged() {
        KafkaTemplateProvider bare = new KafkaTemplateProvider(null, this.fallback, null, null);

        assertThat(bare.getTemplate(Optional.empty())).isSameAs(this.fallback);
        assertThat(this.log.list).extracting(ILoggingEvent::getLevel).contains(Level.WARN);
    }
}
