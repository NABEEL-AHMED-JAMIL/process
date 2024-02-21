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

    private Long sttfId;
    private String sttfName;
    private String description;
    private GLookup status;
    private GLookup formType;
    private GLookup defaultSttf;
    private GLookup homePage;
    private String serviceId;

    public STTFormResponse() {
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

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
