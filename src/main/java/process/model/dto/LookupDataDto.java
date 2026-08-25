package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.sql.Timestamp;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LookupDataDto implements AuditNamed {
    private Long createdBy;

    private String createdByName;
    private String updatedByName;


    private Long lookupId;
    private String lookupValue;
    private String lookupType;
    private String description;
    private Timestamp dateCreated;
    private Boolean encrypted;
    private Long tenantId;
    private Long parentLookupId;
    protected LookupDataDto parent;
    protected Set<LookupDataDto> children;

    public LookupDataDto() {}

    public Long getLookupId() {
        return lookupId;
    }

    public void setLookupId(Long lookupId) {
        this.lookupId = lookupId;
    }

    public String getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(String lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getLookupType() {
        return lookupType;
    }

    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Boolean getEncrypted() {
        return encrypted;
    }

    public void setEncrypted(Boolean encrypted) {
        this.encrypted = encrypted;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getParentLookupId() {
        return parentLookupId;
    }

    public void setParentLookupId(Long parentLookupId) {
        this.parentLookupId = parentLookupId;
    }

    public LookupDataDto getParent() {
        return parent;
    }

    public void setParent(LookupDataDto parent) {
        this.parent = parent;
    }

    public Set<LookupDataDto> getChildren() {
        return children;
    }

    public void setChildren(Set<LookupDataDto> children) {
        this.children = children;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    @Override
    public Long auditKey() {
        return lookupId;
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
