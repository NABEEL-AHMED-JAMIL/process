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
public class STTControlListResponse {

    private Long sttCId;
    private Long sttCOrder;
    private String sttCName;
    private String filedName;
    private GLookup filedType;
    private String description;
    private GLookup mandatory;
    private GLookup status;
    private GLookup sttcDefault;
    private Timestamp dateCreated;
    private Long totalStt;
    private Long totalForm;
    private Long totalSection;

    public STTControlListResponse() {}

    public Long getSttCId() {
        return sttCId;
    }

    public void setSttCId(Long sttCId) {
        this.sttCId = sttCId;
    }

    public Long getSttCOrder() {
        return sttCOrder;
    }

    public void setSttCOrder(Long sttCOrder) {
        this.sttCOrder = sttCOrder;
    }

    public String getSttCName() {
        return sttCName;
    }

    public void setSttCName(String sttCName) {
        this.sttCName = sttCName;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public GLookup isMandatory() {
        return mandatory;
    }

    public void setMandatory(GLookup mandatory) {
        this.mandatory = mandatory;
    }

    public GLookup getMandatory() {
        return mandatory;
    }

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    public GLookup getSttcDefault() {
        return sttcDefault;
    }

    public void setSttcDefault(GLookup sttcDefault) {
        this.sttcDefault = sttcDefault;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Long getTotalStt() {
        return totalStt;
    }

    public void setTotalStt(Long totalStt) {
        this.totalStt = totalStt;
    }

    public Long getTotalForm() {
        return totalForm;
    }

    public void setTotalForm(Long totalForm) {
        this.totalForm = totalForm;
    }

    public Long getTotalSection() {
        return totalSection;
    }

    public void setTotalSection(Long totalSection) {
        this.totalSection = totalSection;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
