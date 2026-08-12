package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A user-configurable Kafka cluster connection (local or remote). Secret fields (saslPassword,
 * ssl*PasswordEnc) are stored as AES-256-GCM ciphertext (see EncryptionUtil) and are never
 * returned to the frontend once saved -- same pattern as AiAgent.apiKey.
 *
 * tenantId null means this profile is platform-wide/shared -- usable by any tenant that hasn't
 * configured its own (mirrors AppUser/SourceJob's own "null tenantId = platform-wide" convention
 * used throughout this app). isDefault marks a tenant's (or the platform's, for a null-tenant
 * row) fallback profile when a SourceTaskType/route doesn't name one explicitly -- see
 * KafkaConnectionResolver for the full resolution order. At most one Active row may have
 * isDefault=true per tenant_id (partial unique index, see the V11 migration) -- this column is a
 * rename of the old single-global-switch "connectionActive" now that there can be many
 * concurrently-effective profiles (one per tenant) instead of exactly one for the whole app.
 * When no profile resolves at all, KafkaConnectionResolver falls back to the original
 * env-var-driven Spring Boot autoconfigured KafkaTemplate (SPRING_KAFKA_BOOTSTRAP_SERVERS) --
 * this table has always been purely additive and still doesn't change default/local-dev
 * behavior with nothing configured.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "kafka_connection_profile", indexes = {
    @Index(name = "idx_kcp_tenant_id", columnList = "tenant_id")
})
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaConnectionProfile {

    @GenericGenerator(
        name = "kafkaConnectionProfileSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "kafka_connection_profile_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "kafka_connection_profile_id")
    @GeneratedValue(generator = "kafkaConnectionProfileSequenceGenerator")
    private Long kafkaConnectionProfileId;

    /** Owning tenant -- null means platform-wide/shared (see class javadoc). */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "profile_name", nullable = false)
    private String profileName;

    /** Free-text label for humans picking a profile in the UI, e.g. "local", "prod-remote-eu"
     * -- purely descriptive, never used in resolution logic. */
    @Column(name = "environment_label")
    private String environmentLabel;

    /** Comma-separated host:port list, e.g. "broker1:9092,broker2:9092". */
    @Column(name = "bootstrap_servers", nullable = false, columnDefinition = "TEXT")
    private String bootstrapServers;

    /** PLAINTEXT, SASL_PLAINTEXT, SASL_SSL, or SSL -- matches Kafka's security.protocol values. */
    @Column(name = "security_protocol", nullable = false)
    private String securityProtocol;

    /** Only set when securityProtocol starts with SASL_ -- e.g. "PLAIN", "SCRAM-SHA-256". */
    @Column(name = "sasl_mechanism")
    private String saslMechanism;

    @Column(name = "sasl_username")
    private String saslUsername;

    /** AES-256-GCM ciphertext (see EncryptionUtil) -- never the plain password. */
    @Column(name = "sasl_password", length = 1000)
    private String saslPassword;

    /** Server-side filesystem path/URI (or object-storage key) to the keystore -- never the raw
     * cert bytes stored in this table. Only set when securityProtocol is SSL/SASL_SSL. */
    @Column(name = "ssl_keystore_location")
    private String sslKeystoreLocation;

    /** AES-256-GCM ciphertext. */
    @Column(name = "ssl_keystore_password_enc", length = 1000)
    private String sslKeystorePasswordEnc;

    /** AES-256-GCM ciphertext -- the private key's own password, distinct from the keystore's. */
    @Column(name = "ssl_key_password_enc", length = 1000)
    private String sslKeyPasswordEnc;

    @Column(name = "ssl_truststore_location")
    private String sslTruststoreLocation;

    /** AES-256-GCM ciphertext. */
    @Column(name = "ssl_truststore_password_enc", length = 1000)
    private String sslTruststorePasswordEnc;

    /** Usually "https"; blank/null disables hostname verification (dev-only escape hatch). */
    @Column(name = "ssl_endpoint_identification_algorithm")
    private String sslEndpointIdentificationAlgorithm;

    /** Free-form NON-secret Kafka client properties as a JSON object string (request.timeout.ms,
     * retries, delivery.timeout.ms, ...) -- the extensibility escape hatch so future Kafka
     * options never require another migration. Contract: never put secrets in here -- they get
     * their own *_enc column above. Stored as plain TEXT (this codebase has no JSONB/JSON
     * column-type mapping set up anywhere yet); parsed/merged into client props at the point of
     * use in KafkaTemplateProvider. */
    @Column(name = "additional_properties", columnDefinition = "TEXT")
    private String additionalProperties;

    /** At most one Active row may have this true per tenant_id (see class javadoc) -- was named
     * connectionActive when there could only ever be one true across the whole table. */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    // Nullable, not "not null" -- ddl-auto=update's ADD COLUMN ... NOT NULL fails outright
    // against a table that already has rows (Postgres requires a DEFAULT for that), and
    // Hibernate just logs+skips that one failed ALTER rather than aborting startup, silently
    // leaving the column missing entirely. The Java-level default below still applies to every
    // new/updated row; treat a null read here (a pre-existing row from before this column
    // existed) the same as "UNTESTED".
    @Column(name = "connection_status")
    private String connectionStatus = "UNTESTED";

    @Column(name = "last_tested_at")
    private Timestamp lastTestedAt;

    @Column(name = "last_test_message", columnDefinition = "TEXT")
    private String lastTestMessage;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    public KafkaConnectionProfile() {}

    public Long getKafkaConnectionProfileId() {
        return kafkaConnectionProfileId;
    }

    public void setKafkaConnectionProfileId(Long kafkaConnectionProfileId) {
        this.kafkaConnectionProfileId = kafkaConnectionProfileId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getEnvironmentLabel() {
        return environmentLabel;
    }

    public void setEnvironmentLabel(String environmentLabel) {
        this.environmentLabel = environmentLabel;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getSecurityProtocol() {
        return securityProtocol;
    }

    public void setSecurityProtocol(String securityProtocol) {
        this.securityProtocol = securityProtocol;
    }

    public String getSaslMechanism() {
        return saslMechanism;
    }

    public void setSaslMechanism(String saslMechanism) {
        this.saslMechanism = saslMechanism;
    }

    public String getSaslUsername() {
        return saslUsername;
    }

    public void setSaslUsername(String saslUsername) {
        this.saslUsername = saslUsername;
    }

    public String getSaslPassword() {
        return saslPassword;
    }

    public void setSaslPassword(String saslPassword) {
        this.saslPassword = saslPassword;
    }

    public String getSslKeystoreLocation() {
        return sslKeystoreLocation;
    }

    public void setSslKeystoreLocation(String sslKeystoreLocation) {
        this.sslKeystoreLocation = sslKeystoreLocation;
    }

    public String getSslKeystorePasswordEnc() {
        return sslKeystorePasswordEnc;
    }

    public void setSslKeystorePasswordEnc(String sslKeystorePasswordEnc) {
        this.sslKeystorePasswordEnc = sslKeystorePasswordEnc;
    }

    public String getSslKeyPasswordEnc() {
        return sslKeyPasswordEnc;
    }

    public void setSslKeyPasswordEnc(String sslKeyPasswordEnc) {
        this.sslKeyPasswordEnc = sslKeyPasswordEnc;
    }

    public String getSslTruststoreLocation() {
        return sslTruststoreLocation;
    }

    public void setSslTruststoreLocation(String sslTruststoreLocation) {
        this.sslTruststoreLocation = sslTruststoreLocation;
    }

    public String getSslTruststorePasswordEnc() {
        return sslTruststorePasswordEnc;
    }

    public void setSslTruststorePasswordEnc(String sslTruststorePasswordEnc) {
        this.sslTruststorePasswordEnc = sslTruststorePasswordEnc;
    }

    public String getSslEndpointIdentificationAlgorithm() {
        return sslEndpointIdentificationAlgorithm;
    }

    public void setSslEndpointIdentificationAlgorithm(String sslEndpointIdentificationAlgorithm) {
        this.sslEndpointIdentificationAlgorithm = sslEndpointIdentificationAlgorithm;
    }

    public String getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(String additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public Timestamp getLastTestedAt() {
        return lastTestedAt;
    }

    public void setLastTestedAt(Timestamp lastTestedAt) {
        this.lastTestedAt = lastTestedAt;
    }

    public String getLastTestMessage() {
        return lastTestMessage;
    }

    public void setLastTestMessage(String lastTestMessage) {
        this.lastTestMessage = lastTestMessage;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
