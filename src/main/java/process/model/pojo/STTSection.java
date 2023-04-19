package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "stt_section")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTSection {

    @GenericGenerator(
        name = "sttSectionSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "sttSection_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="stts_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sttSectionSequenceGenerator")
    private Long sttSId;

    @Column(name = "stts_name")
    private String sttSName;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "stts_order")
    private Long sttSOrder;

    @Column(name = "status",nullable = false)
    private Long status;

    @Column(name = "is_default",
        columnDefinition = "boolean default false")
    private Boolean isDefault;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @ManyToMany(mappedBy = "appUserSTTSections")
    private Set<STTForm> sttForms = new LinkedHashSet<>();

    @ManyToMany(cascade = {
       CascadeType.PERSIST, CascadeType.MERGE
    }, fetch = FetchType.LAZY)
    @JoinTable(	name = "app_user_sttc",
        joinColumns = @JoinColumn(name = "stts_id"),
        inverseJoinColumns = @JoinColumn(name = "sttc_id"))
    private Set<STTControl> appUserSTTControls = new HashSet<>();

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public STTSection() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getSttSId() {
        return sttSId;
    }

    public void setSttSId(Long sttSId) {
        this.sttSId = sttSId;
    }

    public String getSttSName() {
        return sttSName;
    }

    public void setSttSName(String sttSName) {
        this.sttSName = sttSName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getSttSOrder() {
        return sttSOrder;
    }

    public void setSttSOrder(Long sttSOrder) {
        this.sttSOrder = sttSOrder;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public Set<STTForm> getSttForms() {
        return sttForms;
    }

    public void setSttForms(Set<STTForm> sttForms) {
        this.sttForms = sttForms;
    }

    public Set<STTControl> getAppUserSTTControls() {
        return appUserSTTControls;
    }

    public void setAppUserSTTControls(Set<STTControl> appUserSTTControls) {
        this.appUserSTTControls = appUserSTTControls;
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