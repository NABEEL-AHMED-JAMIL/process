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

/**
 * Builds and caches the KafkaTemplate for whichever KafkaConnectionProfile
 * KafkaConnectionResolver resolves to, one cached producer PER PROFILE (keyed by
 * kafkaConnectionProfileId) -- not one for the whole app like before, since there can now be a
 * different effective cluster per tenant/task-type. Building a KafkaProducer is expensive
 * (connection + metadata fetch on construction), so the cache exists specifically so that cost
 * is paid once per cluster, not once per message. When resolution comes back empty (no profile
 * configured for this tenant/task-type at all), falls back to the original env-var-driven
 * Spring Boot autoconfigured KafkaTemplate (SPRING_KAFKA_BOOTSTRAP_SERVERS) -- unchanged from
 * this class's original behavior, so an app with zero profiles configured behaves exactly as
 * before this feature existed.
 * @author Nabeel Ahmed
 */
@Component
public class KafkaTemplateProvider {

    private final Logger logger = LoggerFactory.getLogger(KafkaTemplateProvider.class);
    private final Gson gson = new Gson();

    private final EncryptionUtil encryptionUtil;
    private final KafkaTemplate<String, String> fallbackTemplate;
    private final KafkaProperties kafkaProperties;

    /** One cached producer per profile id -- see class javadoc for why this replaced the old
     * single-slot cache. */
    private final Map<Long, CachedProducer> cache = new ConcurrentHashMap<>();

    public KafkaTemplateProvider(EncryptionUtil encryptionUtil,
        KafkaTemplate<String, String> fallbackTemplate, KafkaProperties kafkaProperties) {
        this.encryptionUtil = encryptionUtil;
        this.fallbackTemplate = fallbackTemplate;
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * Method use to get the KafkaTemplate that should be used for the next publish given an
     * already-resolved profile (see KafkaConnectionResolver) -- the fallback/default cluster
     * when empty.
     * @param profile
     * @return KafkaTemplate<String, String>
     * */
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

    /**
     * Method use to drop one profile's cached template (closing its producer factory first) so
     * the next getTemplate() call for it rebuilds from scratch -- call after
     * updating/disabling/deleting that specific profile so a credential/broker change or
     * deactivation takes effect immediately instead of the next publish reusing a stale producer.
     * @param kafkaConnectionProfileId
     * */
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

    /**
     * Method use to build the common bootstrap/security/SASL/SSL client properties shared by
     * both the producer and the AdminClient (test-connection) -- factored out since a profile's
     * connectivity should be tested with the exact same client config it'll actually publish
     * with.
     * @param profile
     * @return Map<String, Object>
     * */
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
            // Blank (not just unset) explicitly disables hostname verification -- a deliberate
            // dev/self-signed-cert escape hatch, so only skip this when it's truly unset.
            if (profile.getSslEndpointIdentificationAlgorithm() != null) {
                props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, profile.getSslEndpointIdentificationAlgorithm());
            }
        }
        this.mergeAdditionalProperties(props, profile.getAdditionalProperties());
        return props;
    }

    /**
     * Method use to merge a profile's free-form additionalProperties JSON (non-secret Kafka
     * client tuning knobs -- request.timeout.ms, retries, ...) into an already-built props map.
     * Malformed JSON is logged and ignored rather than failing the whole connection, since this
     * is a purely additive convenience field.
     * @param props
     * @param additionalPropertiesJson
     * */
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

    /**
     * Method use to get the AdminClient properties for the env-var-driven default cluster --
     * used when KafkaConnectionResolver has nothing configured for a given tenant/task-type
     * (mirrors getTemplate()'s own fallback).
     * @return Map<String, Object>
     * */
    public Map<String, Object> defaultAdminProps() {
        return new HashMap<>(this.kafkaProperties.buildAdminProperties());
    }

    /**
     * Method use to make sure a topic exists on a specific profile's cluster (or the env-var
     * default, when profile is empty) -- called from SettingServiceImpl whenever a Source
     * TaskType's queueTopicPartition (or its Kafka profile) is added/changed, and once at
     * startup for every existing Source TaskType, so topics are provisioned generically from
     * that data instead of a fixed, hardcoded list (see KafkaProducerConfig). Best-effort: a
     * failure here is logged, not thrown -- ProducerBulkEngine will still surface a clear
     * failure per-job if the topic genuinely isn't reachable when a job actually runs.
     * @param profile
     * @param topic
     * @param partitions
     * */
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
                // race with another creator (broker auto-create, or a concurrent request) -- fine, it's there now
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
        // Was 0 (no retries at all) -- a transient broker hiccup failed the send outright with
        // no recovery attempt. Bounded retries + backoff is standard producer resilience and
        // doesn't change behavior for a healthy cluster, only for a momentarily flaky one.
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 500);
        return props;
    }

    /** One profile's cached producer factory + the template built on top of it. */
    private static class CachedProducer {
        private final DefaultKafkaProducerFactory<String, String> factory;
        private final KafkaTemplate<String, String> template;

        private CachedProducer(DefaultKafkaProducerFactory<String, String> factory, KafkaTemplate<String, String> template) {
            this.factory = factory;
            this.template = template;
        }
    }

}
