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

    private Long sstCId;
    private Long sstCOrder;
    private String sstCName;
    private String filedName;
    private String filedType;
    private String description;
    private boolean mandatory;
    private GLookup status;
    private boolean isDefault;
    private Timestamp dateCreated;
    private Long totalStt;
    private Long totalForm;
    private Long totalSection;

    public STTControlListResponse() {}

    public Long getSstCId() {
        return sstCId;
    }

    public void setSstCId(Long sstCId) {
        this.sstCId = sstCId;
    }

    public Long getSstCOrder() {
        return sstCOrder;
    }

    public void setSstCOrder(Long sstCOrder) {
        this.sstCOrder = sstCOrder;
    }

    public String getSstCName() {
        return sstCName;
    }

    public void setSstCName(String sstCName) {
        this.sstCName = sstCName;
    }

    public String getFiledName() {
        return filedName;
    }

    public void setFiledName(String filedName) {
        this.filedName = filedName;
    }

    public String getFiledType() {
        return filedType;
    }

    public void setFiledType(String filedType) {
        this.filedType = filedType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public GLookup getStatus() {
        return status;
    }

    public void setStatus(GLookup status) {
        this.status = status;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
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
