package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

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

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "profile_name", nullable = false)
    private String profileName;

    @Column(name = "environment_label")
    private String environmentLabel;

    @Column(name = "bootstrap_servers", nullable = false, columnDefinition = "TEXT")
    private String bootstrapServers;

    @Column(name = "security_protocol", nullable = false)
    private String securityProtocol;

    @Column(name = "sasl_mechanism")
    private String saslMechanism;

    @Column(name = "sasl_username")
    private String saslUsername;

    @Column(name = "sasl_password", length = 1000)
    private String saslPassword;

    @Column(name = "ssl_keystore_location")
    private String sslKeystoreLocation;

    @Column(name = "ssl_keystore_password_enc", length = 1000)
    private String sslKeystorePasswordEnc;

    @Column(name = "ssl_key_password_enc", length = 1000)
    private String sslKeyPasswordEnc;

    @Column(name = "ssl_truststore_location")
    private String sslTruststoreLocation;

    @Column(name = "ssl_truststore_password_enc", length = 1000)
    private String sslTruststorePasswordEnc;

    @Column(name = "ssl_endpoint_identification_algorithm")
    private String sslEndpointIdentificationAlgorithm;

    @Column(name = "additional_properties", columnDefinition = "TEXT")
    private String additionalProperties;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

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
