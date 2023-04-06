package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.Set;

/**
 * This LookupData use store the all scheduler detail
 * like last scheduler run
 * and how much chuck data fetch at 1 time
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "lookup_data")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LookupData {

    @GenericGenerator(
        name = "lookupDataSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "lookup_id_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "lookup_id")
    @GeneratedValue(generator = "lookupDataSequenceGenerator")
    private Long lookupId;

    @Column(name = "lookup_type", unique = true)
    private String lookupType;

    @Column(name = "lookup_value",
        columnDefinition = "text")
    private String lookupValue;

    @Column(name = "description")
    private String description;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    @ManyToOne
    @JoinColumn(name = "parent_lookup_id")
    protected LookupData parentLookup;

    @OneToMany(mappedBy = "parentLookup",
        fetch = FetchType.LAZY)
    protected Set<LookupData> lookupChildren;

    @ManyToOne
    @JoinColumn(name="app_user_id", nullable=false)
    private AppUser appUser;

    public LookupData() { }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
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

    public LookupData getParentLookup() {
        return parentLookup;
    }

    public void setParentLookup(LookupData parentLookup) {
        this.parentLookup = parentLookup;
    }

    public Set<LookupData> getLookupChildren() {
        return lookupChildren;
    }

    public void setLookupChildren(Set<LookupData> lookupChildren) {
        this.lookupChildren = lookupChildren;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}