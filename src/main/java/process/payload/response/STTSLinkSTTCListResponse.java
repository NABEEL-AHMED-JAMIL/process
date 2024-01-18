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
public class STTSLinkSTTCListResponse {

    private Long sttsLinkSttcId;
    private Long appUserid;
    private String username;
    private String email;
    private Long sttcId;
    private String sttcName;
    private String filedName;
    private GLookup filedType;
    private GLookup mandatory;
    private GLookup sttcDefault;
    private GLookup sttcDisabled;
    private GLookup status;
    private Timestamp dateCreated;
    private Long sttcOrder;

    public STTSLinkSTTCListResponse() {
    }

    public Long getSttsLinkSttcId() {
        return sttsLinkSttcId;
    }

    public void setSttsLinkSttcId(Long sttsLinkSttcId) {
        this.sttsLinkSttcId = sttsLinkSttcId;
    }

    public Long getAppUserid() {
        return appUserid;
    }

    public void setAppUserid(Long appUserid) {
        this.appUserid = appUserid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
    }

    public String getSttcName() {
        return sttcName;
    }

    public void setSttcName(String sttcName) {
        this.sttcName = sttcName;
    }

    public String getFiledName() {
        return filedName;
    }

    public void setFiledName(String filedName) {
        this.filedName = filedName;
    }

    public GLookup getFiledType() {
        return filedType;
    }

    public void setFiledType(GLookup filedType) {
        this.filedType = filedType;
    }

    public GLookup getMandatory() {
        return mandatory;
    }

    public void setMandatory(GLookup mandatory) {
        this.mandatory = mandatory;
    }

    public GLookup getSttcDefault() {
        return sttcDefault;
    }

    public void setSttcDefault(GLookup sttcDefault) {
        this.sttcDefault = sttcDefault;
    }

    public GLookup getSttcDisabled() {
        return sttcDisabled;
    }

    public void setSttcDisabled(GLookup sttcDisabled) {
        this.sttcDisabled = sttcDisabled;
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

    public Long getSttcOrder() {
        return sttcOrder;
    }

    public void setSttcOrder(Long sttcOrder) {
        this.sttcOrder = sttcOrder;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
