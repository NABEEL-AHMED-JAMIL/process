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
public class STTFLinkSTTListResponse {

    private Long sttfLinkSttId;
    private Long appUserid;
    private String username;
    private String email;
    private Long sttId;
    private String serviceName;
    private GLookup taskType;
    private GLookup sttDefault;
    private GLookup status;
    private Timestamp dateCreated;
    private Long sttfOrder;

    public STTFLinkSTTListResponse() {
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

    public Long getSttId() {
        return sttId;
    }

    public void setSttId(Long sttId) {
        this.sttId = sttId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public GLookup getTaskType() {
        return taskType;
    }

    public void setTaskType(GLookup taskType) {
        this.taskType = taskType;
    }

    public GLookup getSttDefault() {
        return sttDefault;
    }

    public void setSttDefault(GLookup sttDefault) {
        this.sttDefault = sttDefault;
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
