package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTCInteractionsResponse {

    private Long interactionsId;
    private String disabledPattern;
    private String visiblePattern;

    public STTCInteractionsResponse() {
    }

    public Long getInteractionsId() {
        return interactionsId;
    }

    public void setInteractionsId(Long interactionsId) {
        this.interactionsId = interactionsId;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
