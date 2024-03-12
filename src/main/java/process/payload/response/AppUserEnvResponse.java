package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.payload.request.ParseRequest;
import process.util.lookuputil.GLookup;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUserEnvResponse {

    private Long auEnvId;
    private Long envKeyId;
    private String envKey;
    private String envValue;
    private GLookup status;
    private Timestamp dateCreated;
    private AppUserResponse appUser;

    public AppUserEnvResponse() {
    }

    public Long getAuEnvId() {
        return auEnvId;
    }

    public void setAuEnvId(Long auEnvId) {
        this.auEnvId = auEnvId;
    }

    public Long getEnvKeyId() {
        return envKeyId;
    }

    public void setEnvKeyId(Long envKeyId) {
        this.envKeyId = envKeyId;
    }

    public String getEnvKey() {
        return envKey;
    }

    public void setEnvKey(String envKey) {
        this.envKey = envKey;
    }

    public String getEnvValue() {
        return envValue;
    }

    public void setEnvValue(String envValue) {
        this.envValue = envValue;
    }

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public AppUserResponse getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUserResponse appUser) {
        this.appUser = appUser;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
