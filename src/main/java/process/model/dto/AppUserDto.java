package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import process.model.enums.UserRole;
import java.sql.Timestamp;

/**
 * password is write-only -- set on addUser/resetPassword, never populated back on a read (see
 * AppUserServiceImpl's mapping methods).
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUserDto {

    private Long appUserId;
    private String uuid;
    private Long tenantId;
    /** Set on read only, alongside tenantId -- so the UI can show the tenant's name without a
     * separate lookup. */
    private String tenantName;
    /** Set on read only, alongside tenantId -- true unless the user's tenant itself has been
     * deleted/suspended (Tenant.status != Active). Deleting a tenant does NOT change this user's
     * own status field (TenantServiceImpl.changeTenantStatus only touches the tenant row), so
     * without this flag a user under a deleted tenant still reads "Active" here even though
     * login()/refresh() both actually block them -- see AuthServiceImpl.checkAccountAndTenantActive.
     * Null for a PLATFORM_ADMIN (tenantId is null, not scoped to any tenant). */
    private Boolean tenantActive;
    private String username;
    private String password;
    private String fullName;
    private UserRole userRole;
    private Status status;
    private Timestamp dateCreated;
    private Timestamp lastLoginAt;

    public AppUserDto() {}

    public Long getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(Long appUserId) {
        this.appUserId = appUserId;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    public Boolean getTenantActive() {
        return tenantActive;
    }

    public void setTenantActive(Boolean tenantActive) {
        this.tenantActive = tenantActive;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public UserRole getUserRole() {
        return userRole;
    }

    public void setUserRole(UserRole userRole) {
        this.userRole = userRole;
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

    public Timestamp getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Timestamp lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
