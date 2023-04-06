package process.model.pojo;

import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(	name = "app_users",
uniqueConstraints = {
    @UniqueConstraint(columnNames = "username"),
    @UniqueConstraint(columnNames = "email")
})
public class AppUser {

    @GenericGenerator(
        name = "appUserSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "sequence_name", value = "app_users_Seq"),
            @org.hibernate.annotations.Parameter(name = "initial_value", value = "1000"),
            @org.hibernate.annotations.Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "app_user_id")
    @GeneratedValue(generator = "appUserSequenceGenerator")
    private Long appUserId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "time_zone_id",
            nullable = false)
    private String timeZone;
    @Column(name = "username",
            nullable=false)
    private String username;

    @Column(name = "email",
            nullable=false)
    private String email;

    @Column(name = "password",
            nullable=false)
    private String password;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(	name = "app_user_roles",
        joinColumns = @JoinColumn(name = "app_user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> appUserRoles = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(	name = "app_user_source_tt",
        joinColumns = @JoinColumn(name = "app_user_id"),
        inverseJoinColumns = @JoinColumn(name = "source_task_type_id"))
    private Set<SourceTaskType> appUserSourceTaskTypeSet = new HashSet<>();

    @ManyToOne
    @JoinColumn(name = "parent_user_id")
    protected AppUser parentAppUser;

    @OneToMany(mappedBy = "parentAppUser",
        fetch = FetchType.LAZY)
    protected Set<AppUser> appUserChildren;

    @OneToMany(mappedBy="appUser")
    private Set<LookupData> lookupDataSet;

    @OneToMany(mappedBy="appUser")
    private Set<SourceTaskType> sourceTaskTypeSet;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.ORDINAL)
    private Status status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public AppUser() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Set<Role> getAppUserRoles() {
        return appUserRoles;
    }

    public void setAppUserRoles(Set<Role> appUserRoles) {
        this.appUserRoles = appUserRoles;
    }

    public AppUser getParentAppUser() {
        return parentAppUser;
    }

    public void setParentAppUser(AppUser parentAppUser) {
        this.parentAppUser = parentAppUser;
    }

    public Set<SourceTaskType> getAppUserSourceTaskTypeSet() {
        return appUserSourceTaskTypeSet;
    }

    public void setAppUserSourceTaskTypeSet(Set<SourceTaskType> appUserSourceTaskTypeSet) {
        this.appUserSourceTaskTypeSet = appUserSourceTaskTypeSet;
    }

    public Set<AppUser> getAppUserChildren() {
        return appUserChildren;
    }

    public void setAppUserChildren(Set<AppUser> appUserChildren) {
        this.appUserChildren = appUserChildren;
    }

    public Set<LookupData> getLookupDataSet() {
        return lookupDataSet;
    }

    public void setLookupDataSet(Set<LookupData> lookupDataSet) {
        this.lookupDataSet = lookupDataSet;
    }

    public Set<SourceTaskType> getSourceTaskTypeSet() {
        return sourceTaskTypeSet;
    }

    public void setSourceTaskTypeSet(Set<SourceTaskType> sourceTaskTypeSet) {
        this.sourceTaskTypeSet = sourceTaskTypeSet;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
