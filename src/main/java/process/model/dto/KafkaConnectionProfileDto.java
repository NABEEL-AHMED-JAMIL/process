package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaConnectionProfileDto implements AuditNamed {
    private Long createdBy;

    private String createdByName;
    private String updatedByName;


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
    // A blank password means "keep the stored one", which left no way at all to take a credential
    // back out again -- these flags are that way.
    private Boolean clearSaslPassword;
    private String sslKeystoreBucket;
    private String sslKeystoreLocation;
    private String sslKeystorePassword;
    /**
     * A store password that is ALREADY encrypted, as handed back by /kafkaSecret.json when the
     * server built the store itself.
     *
     * It exists because the store is built in one request and the profile saved in another, and the
     * password must not travel in the clear between them. Assigned to the column verbatim -- running
     * the usual encrypt over it would store encrypt(ciphertext) and the store would never open.
     * Write-only: it is never populated on the way out, so a caller can only ever echo back a value
     * this server just gave them, and a value they invent decrypts to nothing and fails their own
     * connection.
     */
    private String sslKeystorePasswordEnc;
    private Boolean sslKeystorePasswordConfigured;
    private Boolean clearSslKeystorePassword;
    private String sslKeyPassword;
    private Boolean sslKeyPasswordConfigured;
    private Boolean clearSslKeyPassword;
    private String sslTruststoreBucket;
    private String sslTruststoreLocation;
    private String sslTruststorePassword;
    /** Its truststore counterpart. See the note on sslKeystorePasswordEnc. */
    private String sslTruststorePasswordEnc;
    /**
     * And the key inside a generated keystore, which this server protects with the same password
     * as the store. Without it a generated keystore could be attached but never opened: the store
     * password came through encrypted and the key password did not come through at all.
     */
    private String sslKeyPasswordEnc;
    private Boolean sslTruststorePasswordConfigured;
    private Boolean clearSslTruststorePassword;
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

    public Boolean getClearSaslPassword() {
        return clearSaslPassword;
    }

    public void setClearSaslPassword(Boolean clearSaslPassword) {
        this.clearSaslPassword = clearSaslPassword;
    }

    public String getSslKeystoreBucket() {
        return sslKeystoreBucket;
    }

    public void setSslKeystoreBucket(String sslKeystoreBucket) {
        this.sslKeystoreBucket = sslKeystoreBucket;
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

    public String getSslTruststorePasswordEnc() {
        return sslTruststorePasswordEnc;
    }

    public void setSslTruststorePasswordEnc(String sslTruststorePasswordEnc) {
        this.sslTruststorePasswordEnc = sslTruststorePasswordEnc;
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

    public Boolean getClearSslKeystorePassword() {
        return clearSslKeystorePassword;
    }

    public void setClearSslKeystorePassword(Boolean clearSslKeystorePassword) {
        this.clearSslKeystorePassword = clearSslKeystorePassword;
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

    public Boolean getClearSslKeyPassword() {
        return clearSslKeyPassword;
    }

    public void setClearSslKeyPassword(Boolean clearSslKeyPassword) {
        this.clearSslKeyPassword = clearSslKeyPassword;
    }

    public String getSslTruststoreBucket() {
        return sslTruststoreBucket;
    }

    public void setSslTruststoreBucket(String sslTruststoreBucket) {
        this.sslTruststoreBucket = sslTruststoreBucket;
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

    public Boolean getClearSslTruststorePassword() {
        return clearSslTruststorePassword;
    }

    public void setClearSslTruststorePassword(Boolean clearSslTruststorePassword) {
        this.clearSslTruststorePassword = clearSslTruststorePassword;
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


    @Override
    public Long auditKey() {
        return kafkaConnectionProfileId;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
