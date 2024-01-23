package process.model.payload.response;

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
public class STTSLinkSTTFListResponse {

    private Long sttsLinkSttfId;
    private Long appUserid;
    private String username;
    private String email;
    private Long formId;
    private String formName;
    private GLookup formType;
    private GLookup formDefault;
    private GLookup status;
    private Timestamp dateCreated;
    private Long sttsOrder;

    public STTSLinkSTTFListResponse() {
    }

    public Long getSttsLinkSttfId() {
        return sttsLinkSttfId;
    }

    public void setSttsLinkSttfId(Long sttsLinkSttfId) {
        this.sttsLinkSttfId = sttsLinkSttfId;
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

    public Long getFormId() {
        return formId;
    }

    public void setFormId(Long formId) {
        this.formId = formId;
    }

    public String getFormName() {
        return formName;
    }

    public void setFormName(String formName) {
        this.formName = formName;
    }

    public GLookup getFormType() {
        return formType;
    }

    public void setFormType(GLookup formType) {
        this.formType = formType;
    }

    public GLookup getFormDefault() {
        return formDefault;
    }

    public void setFormDefault(GLookup formDefault) {
        this.formDefault = formDefault;
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

    public Long getSttsOrder() {
        return sttsOrder;
    }

    public void setSttsOrder(Long sttsOrder) {
        this.sttsOrder = sttsOrder;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
