package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUserEnvRequest {

    private Long envKeyId;
    private Long auEnvId;
    private String envKey;
    private String envValue;
    private Long status;
    private ParseRequest accessUserDetail;
    private ParseRequest assignUserDetail;

    public AppUserEnvRequest() {
    }

    public Long getEnvKeyId() {
        return envKeyId;
    }

    public void setEnvKeyId(Long envKeyId) {
        this.envKeyId = envKeyId;
    }

    public Long getAuEnvId() {
        return auEnvId;
    }

    public void setAuEnvId(Long auEnvId) {
        this.auEnvId = auEnvId;
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

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public ParseRequest getAccessUserDetail() {
        return accessUserDetail;
    }

    public void setAccessUserDetail(ParseRequest accessUserDetail) {
        this.accessUserDetail = accessUserDetail;
    }

    public ParseRequest getAssignUserDetail() {
        return assignUserDetail;
    }

    public void setAssignUserDetail(ParseRequest assignUserDetail) {
        this.assignUserDetail = assignUserDetail;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }


}
