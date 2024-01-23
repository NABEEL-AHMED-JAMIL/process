package process.model.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;


/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTFLinkSTTRequest {

    private Long auSttfId;
    private Long sttId;
    private Long sttfId;
    private Long appUserId;
    private Long sttfOrder;
    private ParseRequest accessUserDetail; // yes

    public STTFLinkSTTRequest() {}

    public Long getAuSttfId() {
        return auSttfId;
    }

    public void setAuSttfId(Long auSttfId) {
        this.auSttfId = auSttfId;
    }

    public Long getSttId() {
        return sttId;
    }

    public void setSttId(Long sttId) {
        this.sttId = sttId;
    }

    public Long getSttfId() {
        return sttfId;
    }

    public void setSttfId(Long sttfId) {
        this.sttfId = sttfId;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(Long appUserId) {
        this.appUserId = appUserId;
    }

    public Long getSttfOrder() {
        return sttfOrder;
    }

    public void setSttfOrder(Long sttfOrder) {
        this.sttfOrder = sttfOrder;
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
