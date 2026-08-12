package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.DatabaseType;
import process.model.enums.Status;
import java.sql.Timestamp;

/**
 * password is write-only -- set it to save/rotate the credential, but it is never populated
 * when this Dto is built from a saved profile (see passwordConfigured instead). Same pattern as
 * AiAgentDto.apiKey/apiKeyConfigured.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DatabaseConnectionProfileDto {

    private Long databaseConnectionProfileId;
    private String profileName;
    private DatabaseType databaseType;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
    private Boolean passwordConfigured;
    private String additionalProperties;
    private Status status;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public DatabaseConnectionProfileDto() {}

    public Long getDatabaseConnectionProfileId() {
        return databaseConnectionProfileId;
    }

    public void setDatabaseConnectionProfileId(Long databaseConnectionProfileId) {
        this.databaseConnectionProfileId = databaseConnectionProfileId;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public void setDatabaseType(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getPasswordConfigured() {
        return passwordConfigured;
    }

    public void setPasswordConfigured(Boolean passwordConfigured) {
        this.passwordConfigured = passwordConfigured;
    }

    public String getAdditionalProperties() {
        return additionalProperties;
    }

    public void setAdditionalProperties(String additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
