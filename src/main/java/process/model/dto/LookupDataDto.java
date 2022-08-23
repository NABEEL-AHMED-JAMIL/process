package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.pojo.LookupData;

import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import java.sql.Timestamp;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LookupDataDto {

    private Long lookupId;
    private String lookupValue;
    private String lookupType;
    private String description;
    private Timestamp dateCreated;

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
}
