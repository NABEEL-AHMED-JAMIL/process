package process.model.pojo;

import com.google.gson.Gson;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.GenericGenerator;


/**
 * @author Nabeel Ahmed
 */
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

    @Column(name = "time_zone_id")
    private Long timeZone;

    @Column(name = "username", nullable=false)
    private String username;

    @Column(name = "email", nullable=false)
    private String email;

    @Column(name = "password", nullable=false)
    private String password;

    @ManyToMany(cascade = {
        CascadeType.PERSIST, CascadeType.MERGE
    }, fetch = FetchType.LAZY)
    @JoinTable(	name = "app_user_roles",
        joinColumns = @JoinColumn(name = "app_user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> appUserRoles = new HashSet<>();

    @OneToMany(mappedBy="appUser")
    private List<AppUserSTT> appUserSTT = new ArrayList<>();

    @OneToMany(mappedBy="appUser")
    private List<STTFLinkSTT> sttfLink = new ArrayList<>();

    @OneToMany(mappedBy="appUser")
    private List<STTSLinkSTTF> sttsLink = new ArrayList<>();

    @OneToMany(mappedBy="appUser")
    private List<STTCLinkSTTS> sttcLink = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "parent_user_id")
    protected AppUser parentAppUser;

    @OneToMany(mappedBy = "parentAppUser", fetch = FetchType.LAZY)
    protected Set<AppUser> appUserChildren;

    @Column(name = "status", nullable = false)
    private Long status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public AppUser() {}

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

    public Long getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(Long timeZone) {
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

    public List<AppUserSTT> getAppUserSTT() {
        return appUserSTT;
    }

    public void setAppUserSTT(List<AppUserSTT> appUserSTT) {
        this.appUserSTT = appUserSTT;
    }

    public List<STTFLinkSTT> getSttfLink() {
        return sttfLink;
    }

    public void setSttfLink(List<STTFLinkSTT> sttfLink) {
        this.sttfLink = sttfLink;
    }

    public List<STTSLinkSTTF> getSttsLink() {
        return sttsLink;
    }

    public void setSttsLink(List<STTSLinkSTTF> sttsLink) {
        this.sttsLink = sttsLink;
    }

    public List<STTCLinkSTTS> getSttcLink() {
        return sttcLink;
    }

    public void setSttcLink(List<STTCLinkSTTS> sttcLink) {
        this.sttcLink = sttcLink;
    }

    public AppUser getParentAppUser() {
        return parentAppUser;
    }

    public void setParentAppUser(AppUser parentAppUser) {
        this.parentAppUser = parentAppUser;
    }

    public Set<AppUser> getAppUserChildren() {
        return appUserChildren;
    }

    public void setAppUserChildren(Set<AppUser> appUserChildren) {
        this.appUserChildren = appUserChildren;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
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
