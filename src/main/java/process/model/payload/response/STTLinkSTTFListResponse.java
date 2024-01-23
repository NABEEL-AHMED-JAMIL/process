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
public class STTLinkSTTFListResponse {

    private Long sttfLinkSttId;
    private Long appUserid;
    private String username;
    private String email;
    private Long sttfId;
    private String sttfName;
    private String description;
    private GLookup formType;
    private GLookup defaultSttf;
    private GLookup homePage;
    private String serviceId;
    private GLookup status;
    private Timestamp dateCreated;
    private Long sttfOrder;

    public STTLinkSTTFListResponse() {
    }

    public Long getSttfLinkSttId() {
        return sttfLinkSttId;
    }

    public void setSttfLinkSttId(Long sttfLinkSttId) {
        this.sttfLinkSttId = sttfLinkSttId;
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

    public GLookup getFormType() {
        return formType;
    }

    public void setFormType(GLookup formType) {
        this.formType = formType;
    }

    public GLookup getDefaultSttf() {
        return defaultSttf;
    }

    public void setDefaultSttf(GLookup defaultSttf) {
        this.defaultSttf = defaultSttf;
    }

    public GLookup getHomePage() {
        return homePage;
    }

    public void setHomePage(GLookup homePage) {
        this.homePage = homePage;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
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

    public Long getSttfOrder() {
        return sttfOrder;
    }

    public void setSttfOrder(Long sttfOrder) {
        this.sttfOrder = sttfOrder;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
