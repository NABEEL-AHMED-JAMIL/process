package process.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.Status;
import java.util.List;

/**
 * A tenant user as the access grid shows them: who they are, what they hold, what that opens.
 *
 * @author Nabeel Ahmed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessPersonDto {

    private Long appUserId;
    private String fullName;
    private String username;
    private String position;
    private Status status;
    private String avatarKey;
    private Long pageAccessProfileId;
    private String pageAccessProfileName;
    /** PageKey keys the person effectively opens, in catalogue order. */
    private List<String> pageKeys;

    public Long getAppUserId() { return appUserId; }
    public void setAppUserId(Long appUserId) { this.appUserId = appUserId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getAvatarKey() { return avatarKey; }
    public void setAvatarKey(String avatarKey) { this.avatarKey = avatarKey; }
    public Long getPageAccessProfileId() { return pageAccessProfileId; }
    public void setPageAccessProfileId(Long pageAccessProfileId) { this.pageAccessProfileId = pageAccessProfileId; }
    public String getPageAccessProfileName() { return pageAccessProfileName; }
    public void setPageAccessProfileName(String pageAccessProfileName) { this.pageAccessProfileName = pageAccessProfileName; }
    public List<String> getPageKeys() { return pageKeys; }
    public void setPageKeys(List<String> pageKeys) { this.pageKeys = pageKeys; }
}
