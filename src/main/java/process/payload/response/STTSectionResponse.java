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
public class STTSectionResponse {

    private Long sttsId;
    private String sttsName;
    private String description;
    private GLookup status;
    private GLookup defaultStts;
    private Timestamp dateCreated;

    public STTSectionResponse() {
    }

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

    public GLookup getDefaultStts() {
        return defaultStts;
    }

    public void setDefaultStts(GLookup defaultStts) {
        this.defaultStts = defaultStts;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
