package process.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 * */
@Configuration
public class KafkaProducerConfig {

    public Logger logger = LogManager.getLogger(KafkaProducerConfig.class);

    private final KafkaProperties kafkaProperties;

    public KafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public Map<String, Object> producerConfigs() {
        // Same durability as a profile-based template. This is the platform's own producer -- the
        // outbox relay's platform.* events and the platform topics -- and never a workspace's: a
        // workspace send that resolves to no profile is refused, not sent here (MIG-45).
        Map<String, Object> props = KafkaTemplateProvider.applyProducerDefaults(new HashMap<>(kafkaProperties.buildProducerProperties()));
        // Event audit E7: the outbox relay hands a batch over at once; idempotent, each key keeps its order through
        // retries. Here only -- a workspace's own brokers (KafkaTemplateProvider) may not grant IdempotentWrite.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return props;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerConfigs());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

}
