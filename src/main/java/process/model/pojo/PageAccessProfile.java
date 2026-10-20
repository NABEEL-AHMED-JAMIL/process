package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;

/**
 * A named bundle of console pages a workspace grants to its tenant users.
 *
 * Owned by exactly one tenant -- there is no platform-wide profile, because the platform admin
 * opens everything anyway and a shared bundle would let one workspace's admin change what
 * another's people can see. At most one profile per workspace is the default (a partial unique
 * index enforces it); a tenant user with no profile of their own gets that one, and a workspace
 * with no default gets every page, which is what every tenant user had before profiles existed.
 *
 * The pages are kept as the catalogue's keys (PageKey) rather than as an enum column, so a key
 * removed from the catalogue later reads as "not granted" instead of failing to load the row.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "page_access_profile", indexes = {
    @Index(name = "idx_page_access_profile_tenant_id", columnList = "tenant_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_page_access_profile_name", columnNames = { "tenant_id", "profile_name" })
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class PageAccessProfile implements Audited {

    @SequenceGenerator(name = "pageAccessProfileSequenceGenerator", sequenceName = "page_access_profile_seq", initialValue = 1000, allocationSize = 1)
    @Id
    @Column(name = "page_access_profile_id")
    @GeneratedValue(generator = "pageAccessProfileSequenceGenerator")
    private Long pageAccessProfileId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "profile_name", nullable = false, length = 100)
    private String profileName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_default", nullable = false)
    private boolean defaultProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.Active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "page_access_profile_page",
        joinColumns = @JoinColumn(name = "page_access_profile_id"))
    @Column(name = "page_key", nullable = false, length = 64)
    private Set<String> pageKeys = new HashSet<>();

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    @Column(name = "date_updated")
    private Timestamp dateUpdated;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    public Long getPageAccessProfileId() { return pageAccessProfileId; }

    public void setPageAccessProfileId(Long pageAccessProfileId) { this.pageAccessProfileId = pageAccessProfileId; }

    public Long getTenantId() { return tenantId; }

    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getProfileName() { return profileName; }

    public void setProfileName(String profileName) { this.profileName = profileName; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }

    public boolean isDefaultProfile() { return defaultProfile; }

    public void setDefaultProfile(boolean defaultProfile) { this.defaultProfile = defaultProfile; }

    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }

    public Set<String> getPageKeys() { return pageKeys; }

    public void setPageKeys(Set<String> pageKeys) { this.pageKeys = pageKeys; }

    public Timestamp getDateCreated() { return dateCreated; }

    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    public Timestamp getDateUpdated() { return dateUpdated; }

    public void setDateUpdated(Timestamp dateUpdated) { this.dateUpdated = dateUpdated; }

    @Override public Long getCreatedBy() { return createdBy; }

    @Override public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    @Override public Long getUpdatedBy() { return updatedBy; }

    @Override public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    @Override public String getCreatedByName() { return createdByName; }

    @Override public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    @Override public String getUpdatedByName() { return updatedByName; }

    @Override public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}
