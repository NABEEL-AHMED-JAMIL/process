package process.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the default/fallback producer (env-var-driven, SPRING_KAFKA_BOOTSTRAP_SERVERS) that
 * KafkaTemplateProvider falls back to when no KafkaConnectionProfile is active.
 *
 * Topics are NOT declared here anymore -- this used to hardcode a fixed NewTopic bean per topic
 * (test/truck/scrapping), so any Source TaskType pointed at a different topic silently had
 * nothing ensuring it existed. Topics are now provisioned generically from Source TaskType data
 * (queueTopicPartition) instead: see KafkaTemplateProvider.ensureTopicExists, called from
 * SettingServiceImpl on every Source TaskType add/update, and from KafkaTopicProvisioner once at
 * startup for every row that already exists.
 * @author Nabeel Ahmed
 */
@Configuration
public class KafkaProducerConfig {

    public Logger logger = LogManager.getLogger(KafkaProducerConfig.class);

    private final KafkaProperties kafkaProperties;

    public KafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public Map<String, Object> producerConfigs() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 0);
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