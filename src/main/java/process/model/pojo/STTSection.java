package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

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
    private Long sttsId;

    @Column(name = "stts_name")
    private String sttsName;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "stts_order")
    private Long sttsOrder;

    @Column(name = "status",nullable = false)
    private Long status;

    @Column(name = "is_default",
        columnDefinition = "boolean default false")
    private Boolean isDefault;

    @OneToMany(mappedBy="stts")
    private List<STTSLinkSTTF> sttsLink = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public STTSection() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getSttsId() {
        return sttsId;
    }

    public void setSttsId(Long sttsId) {
        this.sttsId = sttsId;
    }

    public String getSttsName() {
        return sttsName;
    }

    public void setSttsName(String sttsName) {
        this.sttsName = sttsName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getSttsOrder() {
        return sttsOrder;
    }

    public void setSttsOrder(Long sttsOrder) {
        this.sttsOrder = sttsOrder;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public List<STTSLinkSTTF> getSttsLink() {
        return sttsLink;
    }

    public void setSttsLink(List<STTSLinkSTTF> sttsLink) {
        this.sttsLink = sttsLink;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
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