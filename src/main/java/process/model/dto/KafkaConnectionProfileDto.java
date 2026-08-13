package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.sql.Timestamp;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaConnectionProfileDto {

    private Long kafkaConnectionProfileId;

    private Long tenantId;
    private String profileName;
    private String environmentLabel;
    private String bootstrapServers;
    private String securityProtocol;
    private String saslMechanism;
    private String saslUsername;
    private String saslPassword;
    private Boolean saslPasswordConfigured;
    private String sslKeystoreLocation;
    private String sslKeystorePassword;
    private Boolean sslKeystorePasswordConfigured;
    private String sslKeyPassword;
    private Boolean sslKeyPasswordConfigured;
    private String sslTruststoreLocation;
    private String sslTruststorePassword;
    private Boolean sslTruststorePasswordConfigured;
    private String sslEndpointIdentificationAlgorithm;

    private String additionalProperties;
    private Boolean isDefault;
    private Status status;
    private String connectionStatus;
    private Timestamp lastTestedAt;
    private String lastTestMessage;
    private Timestamp dateCreated;

    public KafkaConnectionProfileDto() {}

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

    public Boolean getSaslPasswordConfigured() {
        return saslPasswordConfigured;
    }

    public void setSaslPasswordConfigured(Boolean saslPasswordConfigured) {
        this.saslPasswordConfigured = saslPasswordConfigured;
    }

    public String getSslKeystoreLocation() {
        return sslKeystoreLocation;
    }

    public void setSslKeystoreLocation(String sslKeystoreLocation) {
        this.sslKeystoreLocation = sslKeystoreLocation;
    }

    public String getSslKeystorePassword() {
        return sslKeystorePassword;
    }

    public void setSslKeystorePassword(String sslKeystorePassword) {
        this.sslKeystorePassword = sslKeystorePassword;
    }

    public Boolean getSslKeystorePasswordConfigured() {
        return sslKeystorePasswordConfigured;
    }

    public void setSslKeystorePasswordConfigured(Boolean sslKeystorePasswordConfigured) {
        this.sslKeystorePasswordConfigured = sslKeystorePasswordConfigured;
    }

    public String getSslKeyPassword() {
        return sslKeyPassword;
    }

    public void setSslKeyPassword(String sslKeyPassword) {
        this.sslKeyPassword = sslKeyPassword;
    }

    public Boolean getSslKeyPasswordConfigured() {
        return sslKeyPasswordConfigured;
    }

    public void setSslKeyPasswordConfigured(Boolean sslKeyPasswordConfigured) {
        this.sslKeyPasswordConfigured = sslKeyPasswordConfigured;
    }

    public String getSslTruststoreLocation() {
        return sslTruststoreLocation;
    }

    public void setSslTruststoreLocation(String sslTruststoreLocation) {
        this.sslTruststoreLocation = sslTruststoreLocation;
    }

    public String getSslTruststorePassword() {
        return sslTruststorePassword;
    }

    public void setSslTruststorePassword(String sslTruststorePassword) {
        this.sslTruststorePassword = sslTruststorePassword;
    }

    public Boolean getSslTruststorePasswordConfigured() {
        return sslTruststorePasswordConfigured;
    }

    public void setSslTruststorePasswordConfigured(Boolean sslTruststorePasswordConfigured) {
        this.sslTruststorePasswordConfigured = sslTruststorePasswordConfigured;
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
