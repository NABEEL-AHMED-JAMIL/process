package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTFormRequest {
    private Long sttfId;
    private String sttfName; // yes
    private String description; // yes
    private Long status;
    private boolean defaultSttf;
    private ParseRequest accessUserDetail; // yes

    public STTFormRequest() {
    }

    public Long getSttfId() {
        return sttfId;
    }

    public void setSttfId(Long sttfId) {
        this.sttfId = sttfId;
    }

    public String getSttfName() {
        return sttfName;
    }

    public void setSttfName(String sttfName) {
        this.sttfName = sttfName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public boolean isDefaultSttf() {
        return defaultSttf;
    }

    public void setDefaultSttf(boolean defaultSttf) {
        this.defaultSttf = defaultSttf;
    }

    public ParseRequest getAccessUserDetail() {
        return accessUserDetail;
    }

    public void setAccessUserDetail(ParseRequest accessUserDetail) {
        this.accessUserDetail = accessUserDetail;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
