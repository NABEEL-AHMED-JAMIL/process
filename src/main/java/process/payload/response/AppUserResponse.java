package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.lookuputil.GLookup;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUserResponse {

    private Long appUserId;
    private String firstName;
    private String lastName;
    private String timeZone;
    private String username;
    private String email;
    private Set<RoleResponse> roleResponse;
    protected AppUserResponse parentAppUser;
    private GLookup status;
    private Timestamp dateCreated;
    private List<AppUserResponse> subAppUser;

    public AppUserResponse() {}

    public AppUserResponse(Long appUserId, String username) {
        this.appUserId = appUserId;
        this.username = username;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(Long appUserId) {
        this.appUserId = appUserId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
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

    public Set<RoleResponse> getRoleResponse() {
        return roleResponse;
    }

    public void setRoleResponse(Set<RoleResponse> roleResponse) {
        this.roleResponse = roleResponse;
    }

    public AppUserResponse getParentAppUser() {
        return parentAppUser;
    }

    public void setParentAppUser(AppUserResponse parentAppUser) {
        this.parentAppUser = parentAppUser;
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

    public List<AppUserResponse> getSubAppUser() {
        return subAppUser;
    }

    public void setSubAppUser(List<AppUserResponse> subAppUser) {
        this.subAppUser = subAppUser;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
