package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.Status;
import java.sql.Timestamp;
import java.util.List;

/**
 * An access profile as the console sees it.
 *
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageAccessProfileDto {

    private Long pageAccessProfileId;
    private Long tenantId;
    private String profileName;
    private String description;
    private Boolean defaultProfile;
    private Status status;
    /** PageKey keys, in catalogue order on the way out. */
    private List<String> pageKeys;
    /** How many active people hold it -- shown on the card, and what refuses a delete. */
    private Long userCount;
    private List<String> userNames;
    private Timestamp dateCreated;
    private Timestamp dateUpdated;
    private String createdByName;
    private String updatedByName;

    public Long getPageAccessProfileId() { return pageAccessProfileId; }
    public void setPageAccessProfileId(Long pageAccessProfileId) { this.pageAccessProfileId = pageAccessProfileId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getProfileName() { return profileName; }
    public void setProfileName(String profileName) { this.profileName = profileName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getDefaultProfile() { return defaultProfile; }
    public void setDefaultProfile(Boolean defaultProfile) { this.defaultProfile = defaultProfile; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public List<String> getPageKeys() { return pageKeys; }
    public void setPageKeys(List<String> pageKeys) { this.pageKeys = pageKeys; }
    public Long getUserCount() { return userCount; }
    public void setUserCount(Long userCount) { this.userCount = userCount; }
    public List<String> getUserNames() { return userNames; }
    public void setUserNames(List<String> userNames) { this.userNames = userNames; }
    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }
    public Timestamp getDateUpdated() { return dateUpdated; }
    public void setDateUpdated(Timestamp dateUpdated) { this.dateUpdated = dateUpdated; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}
