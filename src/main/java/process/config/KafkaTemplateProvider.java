package process.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import process.model.pojo.KafkaConnectionProfile;
import process.util.EncryptionUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaTemplateProvider {

    private final Logger logger = LoggerFactory.getLogger(KafkaTemplateProvider.class);
    private final Gson gson = new Gson();

    private final EncryptionUtil encryptionUtil;
    private final KafkaTemplate<String, String> fallbackTemplate;
    private final KafkaProperties kafkaProperties;

    private final Map<Long, CachedProducer> cache = new ConcurrentHashMap<>();

    public KafkaTemplateProvider(EncryptionUtil encryptionUtil,
        KafkaTemplate<String, String> fallbackTemplate, KafkaProperties kafkaProperties) {
        this.encryptionUtil = encryptionUtil;
        this.fallbackTemplate = fallbackTemplate;
        this.kafkaProperties = kafkaProperties;
    }

    public KafkaTemplate<String, String> getTemplate(Optional<KafkaConnectionProfile> profile) {
        if (!profile.isPresent()) {
            return this.fallbackTemplate;
        }
        KafkaConnectionProfile p = profile.get();
        CachedProducer cached = this.cache.computeIfAbsent(p.getKafkaConnectionProfileId(), id -> {
            this.logger.info("Building KafkaTemplate for profile '{}' ({}), tenantId={}.",
                p.getProfileName(), p.getBootstrapServers(), p.getTenantId());
            DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(this.producerProps(p));
            return new CachedProducer(factory, new KafkaTemplate<>(factory));
        });
        return cached.template;
    }

    public void invalidate(Long kafkaConnectionProfileId) {
        CachedProducer removed = this.cache.remove(kafkaConnectionProfileId);
        if (removed != null) {
            try {
                removed.factory.destroy();
            } catch (Exception ex) {
                this.logger.warn("Error closing Kafka producer factory for profile {}: {}", kafkaConnectionProfileId, ex.getMessage());
            }
        }
    }

    public Map<String, Object> commonClientProps(KafkaConnectionProfile profile) {
        Map<String, Object> props = new HashMap<>();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, profile.getBootstrapServers());
        String securityProtocol = profile.getSecurityProtocol();
        if (securityProtocol != null && !securityProtocol.trim().isEmpty()) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }
        if (securityProtocol != null && securityProtocol.startsWith("SASL_")) {
            props.put(SaslConfigs.SASL_MECHANISM, profile.getSaslMechanism());
            String username = profile.getSaslUsername();
            String password = profile.getSaslPassword() == null ? null : this.encryptionUtil.decrypt(profile.getSaslPassword());
            String loginModule = "SCRAM-SHA-256".equals(profile.getSaslMechanism()) || "SCRAM-SHA-512".equals(profile.getSaslMechanism())
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
            props.put(SaslConfigs.SASL_JAAS_CONFIG, String.format(
                "%s required username=\"%s\" password=\"%s\";", loginModule, username, password));
        }
        if ("SSL".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol)) {
            if (profile.getSslTruststoreLocation() != null && !profile.getSslTruststoreLocation().trim().isEmpty()) {
                props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, profile.getSslTruststoreLocation());
                if (profile.getSslTruststorePasswordEnc() != null) {
                    props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, this.encryptionUtil.decrypt(profile.getSslTruststorePasswordEnc()));
                }
            }
            if (profile.getSslKeystoreLocation() != null && !profile.getSslKeystoreLocation().trim().isEmpty()) {
                props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, profile.getSslKeystoreLocation());
                if (profile.getSslKeystorePasswordEnc() != null) {
                    props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, this.encryptionUtil.decrypt(profile.getSslKeystorePasswordEnc()));
                }
                if (profile.getSslKeyPasswordEnc() != null) {
                    props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, this.encryptionUtil.decrypt(profile.getSslKeyPasswordEnc()));
                }
            }

            if (profile.getSslEndpointIdentificationAlgorithm() != null) {
                props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, profile.getSslEndpointIdentificationAlgorithm());
            }
        }
        this.mergeAdditionalProperties(props, profile.getAdditionalProperties());
        return props;
    }

    private void mergeAdditionalProperties(Map<String, Object> props, String additionalPropertiesJson) {
        if (additionalPropertiesJson == null || additionalPropertiesJson.trim().isEmpty()) {
            return;
        }
        try {
            Map<String, String> extra = this.gson.fromJson(additionalPropertiesJson, new TypeToken<Map<String, String>>() {}.getType());
            if (extra != null) {
                props.putAll(extra);
            }
        } catch (Exception ex) {
            this.logger.warn("Could not parse additionalProperties JSON, ignoring: {}", ex.getMessage());
        }
    }

    public Map<String, Object> defaultAdminProps() {
        return new HashMap<>(this.kafkaProperties.buildAdminProperties());
    }

    public void ensureTopicExists(Optional<KafkaConnectionProfile> profile, String topic, int partitions) {
        if (topic == null || topic.trim().isEmpty()) {
            return;
        }
        Map<String, Object> adminProps = profile.map(this::commonClientProps).orElseGet(this::defaultAdminProps);
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> existingTopics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
            if (existingTopics.contains(topic)) {
                return;
            }
            adminClient.createTopics(Collections.singleton(new NewTopic(topic, partitions, (short) 1)))
                .all().get(10, TimeUnit.SECONDS);
            this.logger.info("Auto-created Kafka topic '{}' with {} partition(s).", topic, partitions);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof TopicExistsException) {

                return;
            }
            this.logger.warn("Could not auto-create Kafka topic '{}': {}", topic, ex.getMessage());
        } catch (Exception ex) {
            this.logger.warn("Could not auto-create Kafka topic '{}': {}", topic, ex.getMessage());
        }
    }

    private Map<String, Object> producerProps(KafkaConnectionProfile profile) {
        Map<String, Object> props = this.commonClientProps(profile);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 500);
        return props;
    }

    private static class CachedProducer {
        private final DefaultKafkaProducerFactory<String, String> factory;
        private final KafkaTemplate<String, String> template;

        private CachedProducer(DefaultKafkaProducerFactory<String, String> factory, KafkaTemplate<String, String> template) {
            this.factory = factory;
            this.template = template;
        }
    }

}
