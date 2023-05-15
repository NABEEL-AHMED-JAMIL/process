package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTSLinkSTTCRequest {

    private Long auSttcId;
    private Long sttsId;
    private Long sttcId;
    private Long appUserId;
    private ParseRequest accessUserDetail; // yes

    public STTSLinkSTTCRequest() {}

    public Long getAuSttcId() {
        return auSttcId;
    }

    public void setAuSttcId(Long auSttcId) {
        this.auSttcId = auSttcId;
    }

    public Long getSttsId() {
        return sttsId;
    }

    public void setSttsId(Long sttsId) {
        this.sttsId = sttsId;
    }

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(Long appUserId) {
        this.appUserId = appUserId;
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
