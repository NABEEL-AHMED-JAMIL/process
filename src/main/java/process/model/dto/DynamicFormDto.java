package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.sql.Timestamp;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicFormDto implements AuditNamed {
    private Long createdBy;

    private String createdByName;
    private String updatedByName;


    private Long dynamicFormId;
    private String formName;
    private String description;
    private Status status;
    private Timestamp dateCreated;
    private Integer totalFields;
    private List<DynamicFormFieldDto> fields;

    private String uuid;

    public DynamicFormDto() {
    }

    public Long getDynamicFormId() {
        return dynamicFormId;
    }

    public void setDynamicFormId(Long dynamicFormId) {
        this.dynamicFormId = dynamicFormId;
    }

    public String getFormName() {
        return formName;
    }

    public void setFormName(String formName) {
        this.formName = formName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Integer getTotalFields() {
        return totalFields;
    }

    public void setTotalFields(Integer totalFields) {
        this.totalFields = totalFields;
    }

    public List<DynamicFormFieldDto> getFields() {
        return fields;
    }

    public void setFields(List<DynamicFormFieldDto> fields) {
        this.fields = fields;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    @Override
    public Long auditKey() {
        return dynamicFormId;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
