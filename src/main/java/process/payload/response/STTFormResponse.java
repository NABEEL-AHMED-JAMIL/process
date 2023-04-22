package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.lookuputil.GLookup;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTFormResponse {

    private Long sttFId;
    private String sttFName;
    private String description;
    private GLookup status;
    private GLookup defaultSttf;

    public STTFormResponse() {
    }

    public Long getSttFId() {
        return sttFId;
    }

    public void setSttFId(Long sttFId) {
        this.sttFId = sttFId;
    }

    public String getSttFName() {
        return sttFName;
    }

    public void setSttFName(String sttFName) {
        this.sttFName = sttFName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    public GLookup getDefaultSttf() {
        return defaultSttf;
    }

    public void setDefaultSttf(GLookup defaultSttf) {
        this.defaultSttf = defaultSttf;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
