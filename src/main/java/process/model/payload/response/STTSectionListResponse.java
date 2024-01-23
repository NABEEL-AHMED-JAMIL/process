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
public class STTSectionListResponse {

    private Long sttsId;
    private String sttsName;
    private String description;
    private GLookup status;
    private GLookup sttsDefault;
    private Timestamp dateCreated;
    private Long totalSTT = 0l;
    private Long totalForm = 0l;
    private Long totalControl = 0l;

    public STTSectionListResponse() {}

    public Long getSttsId() {
        return sttsId;
    }

    public void setSttsId(Long sttsId) {
        this.sttsId = sttsId;
    }

    public String getSttsName() {
        return sttsName;
    }

    public void setSttsName(String sttsName) {
        this.sttsName = sttsName;
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

    public GLookup getSttsDefault() {
        return sttsDefault;
    }

    public void setSttsDefault(GLookup sttsDefault) {
        this.sttsDefault = sttsDefault;
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
