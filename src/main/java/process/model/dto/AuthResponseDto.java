package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponseDto {

    private String accessToken;
    private String refreshToken;
    private String username;
    private String fullName;
    private String userRole;
    private Long tenantId;
    private Long appUserId;
    /** Carried at sign-in so the header can show the picture before anything else is fetched. */
    private String avatarBucket;
    private String avatarKey;
    /**
     * Same flag the profile screen reads off AppUserDto, carried at sign-in so an account opened
     * with a one-time password is asked to replace it there and then, rather than only when the
     * user happens to open their profile.
     *
     * Boxed like every other field here because refresh answers with a partial response that the
     * console merges over the session it already has: a primitive would send false on every
     * refresh and quietly settle a debt the account still owes.
     */
    private Boolean mustChangePassword;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getAppUserId() {
        return appUserId;
    }

    public void setAppUserId(Long appUserId) {
        this.appUserId = appUserId;
    }


    public String getAvatarBucket() {
        return avatarBucket;
    }

    public void setAvatarBucket(String avatarBucket) {
        this.avatarBucket = avatarBucket;
    }

    public String getAvatarKey() {
        return avatarKey;
    }

    public void setAvatarKey(String avatarKey) {
        this.avatarKey = avatarKey;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }
}
