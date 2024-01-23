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
public class STTListResponse {

    private Long sttId;
    private String serviceName;
    private String description;
    private GLookup status;
    private GLookup taskType;
    private GLookup sttDefault;
    private GLookup homePage;
    private String serviceId;
    private CredentialResponse credential;
    private Timestamp dateCreated;
    private Long totalUser = 0l;
    private Long totalTask = 0l;
    private Long totalForm = 0l;

    public STTListResponse() {
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

    public CredentialResponse getCredential() {
        return credential;
    }

    public void setCredential(CredentialResponse credential) {
        this.credential = credential;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Long getTotalUser() {
        return totalUser;
    }

    public void setTotalUser(Long totalUser) {
        this.totalUser = totalUser;
    }

    public Long getTotalTask() {
        return totalTask;
    }

    public void setTotalTask(Long totalTask) {
        this.totalTask = totalTask;
    }

    public Long getTotalForm() {
        return totalForm;
    }

    public void setTotalForm(Long totalForm) {
        this.totalForm = totalForm;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
