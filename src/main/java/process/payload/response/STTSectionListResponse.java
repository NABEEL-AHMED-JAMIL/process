package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.lookuputil.GLookup;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTSectionListResponse {

    private Long sttSId;
    private String sttSName;
    private String description;
    private Long sttSOrder;
    private GLookup status;
    private boolean isDefault;
    private Timestamp dateCreated;
    private Long totalSTT = 0l;
    private Long totalForm = 0l;
    private Long totalControl = 0l;

    public STTSectionListResponse() {}

    public Long getSttSId() {
        return sttSId;
    }

    public void setSttSId(Long sttSId) {
        this.sttSId = sttSId;
    }

    public String getSttSName() {
        return sttSName;
    }

    public void setSttSName(String sttSName) {
        this.sttSName = sttSName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getSttSOrder() {
        return sttSOrder;
    }

    public void setSttSOrder(Long sttSOrder) {
        this.sttSOrder = sttSOrder;
    }

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Long getTotalSTT() {
        return totalSTT;
    }

    public void setTotalSTT(Long totalSTT) {
        this.totalSTT = totalSTT;
    }

    public Long getTotalForm() {
        return totalForm;
    }

    public void setTotalForm(Long totalForm) {
        this.totalForm = totalForm;
    }

    public Long getTotalControl() {
        return totalControl;
    }

    public void setTotalControl(Long totalControl) {
        this.totalControl = totalControl;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
