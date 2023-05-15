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

    private Long sttcId;
    private Long sttcOrder;
    private String sttcName;
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

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
    }

    public Long getSttcOrder() {
        return sttcOrder;
    }

    public void setSttcOrder(Long sttcOrder) {
        this.sttcOrder = sttcOrder;
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
