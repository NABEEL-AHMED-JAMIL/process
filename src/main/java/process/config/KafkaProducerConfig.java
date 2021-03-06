package process.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Configuration
public class KafkaProducerConfig {

    public Logger logger = LogManager.getLogger(KafkaProducerConfig.class);

    @Value("${tpd.test-topic}")
    private String testTopic;

    @Value("${tpd.scrapping-topic}")
    private String scrappingTopic;

    @Value("${tpd.extraction-topic}")
    private String extractionTopic;

    @Value("${tpd.comparison-topic}")
    private String comparisonTopic;

    @Autowired
    private KafkaProperties kafkaProperties;

    @Bean
    public Map<String, Object> producerConfigs() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
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

    @Bean
    public NewTopic testTopic() {
        return new NewTopic(testTopic, 2, (short) 1);
    }

    @Bean
    public NewTopic scrapperTopic() {
        return new NewTopic(scrappingTopic, 3, (short) 1);
    }

    @Bean
    public NewTopic comparisonTopic() {
        return new NewTopic(comparisonTopic, 3, (short) 1);
    }

    @Bean
    public NewTopic extractionTopic() {
        return new NewTopic(extractionTopic, 2, (short) 1);
    }

}
