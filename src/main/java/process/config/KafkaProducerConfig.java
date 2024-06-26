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

    /**
     * @Value("${tpd.data-analytics-topic}")
     * private String dataAnalyticsTopic;
     * */
    @Value("${tpd.test-topic}")
    private String testTopic;

    @Value("${tpd.truck-topic}")
    private String trucksTopic;

    @Value("${tpd.scrapping-topic}")
    private String scrappingTopic;

    @Autowired
    private KafkaProperties kafkaProperties;

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

    @Bean
    public NewTopic testTopic() {
        return new NewTopic(this.testTopic, 2, (short) 1);
    }

    @Bean
    public NewTopic trucksTopic() {
        return new NewTopic(this.trucksTopic, 2, (short) 2);
    }

    @Bean
    public NewTopic scrappingTopic() {
        return new NewTopic(this.scrappingTopic, 2, (short) 1);
    }

}