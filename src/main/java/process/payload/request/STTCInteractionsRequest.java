package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTCInteractionsRequest {

    private Long interactionsId;
    private Long auSttsId;
    private Long sttcId;
    private String disabledPattern;
    private String visiblePattern;
    private ParseRequest accessUserDetail;

    public STTCInteractionsRequest() {
    }

    public Long getInteractionsId() {
        return interactionsId;
    }

    public void setInteractionsId(Long interactionsId) {
        this.interactionsId = interactionsId;
    }

    public Long getAuSttsId() {
        return auSttsId;
    }

    public void setAuSttsId(Long auSttsId) {
        this.auSttsId = auSttsId;
    }

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
    }

    public String getDisabledPattern() {
        return disabledPattern;
    }

    public void setDisabledPattern(String disabledPattern) {
        this.disabledPattern = disabledPattern;
    }

    public String getVisiblePattern() {
        return visiblePattern;
    }

    public void setVisiblePattern(String visiblePattern) {
        this.visiblePattern = visiblePattern;
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
