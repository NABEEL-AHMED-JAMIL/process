package process.model.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import java.sql.Timestamp;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LookupDataResponse {

    private Long lookupId;
    private String lookupType;
    private String lookupValue;
    private String description;
    private Timestamp dateCreated;

    protected Set<LookupDataResponse> lookupChildren;

    public LookupDataResponse() {
    }

    public Long getLookupId() {
        return lookupId;
    }

    public void setLookupId(Long lookupId) {
        this.lookupId = lookupId;
    }

    public String getLookupType() {
        return lookupType;
    }

    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public String getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(String lookupValue) {
        this.lookupValue = lookupValue;
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

    public Set<LookupDataResponse> getLookupChildren() {
        return lookupChildren;
    }

    public void setLookupChildren(Set<LookupDataResponse> lookupChildren) {
        this.lookupChildren = lookupChildren;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
