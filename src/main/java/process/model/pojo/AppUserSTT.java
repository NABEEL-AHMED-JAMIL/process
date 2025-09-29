package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "app_user_stt")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUserSTT {

    @GenericGenerator(
        name = "auSttSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "sequence_name", value = "au_stt_Seq"),
            @org.hibernate.annotations.Parameter(name = "initial_value", value = "1000"),
            @org.hibernate.annotations.Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "au_stt_id")
    @GeneratedValue(generator = "auSttSequenceGenerator")
    private Long auSttId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stt_id", nullable = false)
    private STT stt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    @OneToMany(mappedBy="stt")
    private List<STTFLinkSTT> sttfLink = new ArrayList<>();

    @Column(name = "status", nullable = false)
    private Long status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public AppUserSTT() {}

    public AppUserSTT(STT stt, AppUser appUser, Long status) {
        this.stt = stt;
        this.appUser = appUser;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getAuSttId() {
        return auSttId;
    }

    public void setAuSttId(Long auSttId) {
        this.auSttId = auSttId;
    }

    public STT getStt() {
        return stt;
    }

    public void setStt(STT stt) {
        this.stt = stt;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(AppUser createdBy) {
        this.createdBy = createdBy;
    }

    public List<STTFLinkSTT> getSttfLink() {
        return sttfLink;
    }

    public void setSttfLink(List<STTFLinkSTT> sttfLink) {
        this.sttfLink = sttfLink;
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
