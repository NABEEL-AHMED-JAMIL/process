package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.sql.Timestamp;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicFormSubmissionDto {

    private Long dynamicFormSubmissionId;
    private Long dynamicFormId;
    private String uuid;

    private Map<String, Object> payload;

    private Timestamp dateCreated;

    public DynamicFormSubmissionDto() {
    }

    public Long getDynamicFormSubmissionId() {
        return dynamicFormSubmissionId;
    }

    public void setDynamicFormSubmissionId(Long dynamicFormSubmissionId) {
        this.dynamicFormSubmissionId = dynamicFormSubmissionId;
    }

    public Long getDynamicFormId() {
        return dynamicFormId;
    }

    public void setDynamicFormId(Long dynamicFormId) {
        this.dynamicFormId = dynamicFormId;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
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
