package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import process.model.enums.UserRole;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A login-capable user. tenantId is null for PLATFORM_ADMIN (not scoped to any tenant) and
 * required for TENANT_ADMIN/TENANT_USER. password is a BCrypt hash (see PasswordEncoder bean
 * in SecurityConfig), never the plain password.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "app_user", indexes = {
    // Queried by AppUserServiceImpl.listUsers/scopedFind (findByTenantIdAndStatusNotOrderBy...,
    // countByTenantIdAndStatusNot) on every Tenant Admin user-list load.
    @Index(name = "idx_app_user_tenant_id", columnList = "tenant_id")
})
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUser {

    @GenericGenerator(
        name = "appUserSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "app_user_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "app_user_id")
    @GeneratedValue(generator = "appUserSequenceGenerator")
    private Long appUserId;

    @Column(name = "uuid", unique = true, length = 36)
    private String uuid;

    /** Null for PLATFORM_ADMIN; required for TENANT_ADMIN/TENANT_USER. */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** Login identifier -- an email address, globally unique across all tenants. */
    @Column(name = "username", nullable = false, unique = true)
    private String username;

    /** BCrypt hash (see PasswordEncoder) -- never the plain password. */
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "user_role", nullable = false)
    @Enumerated(EnumType.STRING)
    private UserRole userRole;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    @Column(name = "last_login_at")
    private Timestamp lastLoginAt;

    public AppUser() {}

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
