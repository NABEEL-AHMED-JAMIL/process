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
public class STTCLinkSTTSListResponse {

    private Long sttcLinkSttsId;
    private Long appUserid;
    private String username;
    private String email;
    private Long sttsId;
    private String sttsName;
    private String description;
    private Long sttsOrder;
    private GLookup sttsDefault;
    private GLookup status;
    private Timestamp dateCreated;

    public STTCLinkSTTSListResponse() {
    }

    public Long getSttcLinkSttsId() {
        return sttcLinkSttsId;
    }

    public void setSttcLinkSttsId(Long sttcLinkSttsId) {
        this.sttcLinkSttsId = sttcLinkSttsId;
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

    public Long getSttsOrder() {
        return sttsOrder;
    }

    public void setSttsOrder(Long sttsOrder) {
        this.sttsOrder = sttsOrder;
    }

    public GLookup getSttsDefault() {
        return sttsDefault;
    }

    public void setSttsDefault(GLookup sttsDefault) {
        this.sttsDefault = sttsDefault;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
