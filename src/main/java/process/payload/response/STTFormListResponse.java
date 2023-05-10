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
public class STTFormListResponse {

    private Long sttFId;
    private String sttFName;
    private String description;
    private GLookup status;
    private GLookup sttfDefault;
    private GLookup formType;
    private Timestamp dateCreated;
    private Long totalStt = 0l;
    private Long totalSection = 0l;
    private Long totalControl = 0l;

    public STTFormListResponse() {
    }

    public Long getSttFId() {
        return sttFId;
    }

    public void setSttFId(Long sttFId) {
        this.sttFId = sttFId;
    }

    public String getSttFName() {
        return sttFName;
    }

    public void setSttFName(String sttFName) {
        this.sttFName = sttFName;
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

    public GLookup getSttfDefault() {
        return sttfDefault;
    }

    public void setSttfDefault(GLookup sttfDefault) {
        this.sttfDefault = sttfDefault;
    }

    public GLookup getFormType() {
        return formType;
    }

    public void setFormType(GLookup formType) {
        this.formType = formType;
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

    public Long getTotalSection() {
        return totalSection;
    }

    public void setTotalSection(Long totalSection) {
        this.totalSection = totalSection;
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
