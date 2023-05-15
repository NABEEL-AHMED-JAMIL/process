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
@Table(name = "stts_link_sttf")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTSLinkSTTF {

    @GenericGenerator(
        name = "auSttsSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "sequence_name", value = "au_stts_Seq"),
            @org.hibernate.annotations.Parameter(name = "initial_value", value = "1000"),
            @org.hibernate.annotations.Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "au_stts_id")
    @GeneratedValue(generator = "auSttsSequenceGenerator")
    private Long auSttsId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stts_id", nullable = false)
    private STTSection stts;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sttf_id", nullable = false)
    private STTForm sttf;

    @OneToMany(mappedBy="stts")
    private List<STTCLinkSTTS> sttcLink = new ArrayList<>();

    @Column(name = "status", nullable = false)
    private Long status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public STTSLinkSTTF() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getAuSttsId() {
        return auSttsId;
    }

    public void setAuSttsId(Long auSttsId) {
        this.auSttsId = auSttsId;
    }

    public STTSection getStts() {
        return stts;
    }

    public void setStts(STTSection stts) {
        this.stts = stts;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public STTForm getSttf() {
        return sttf;
    }

    public void setSttf(STTForm sttf) {
        this.sttf = sttf;
    }

    public List<STTCLinkSTTS> getSttcLink() {
        return sttcLink;
    }

    public void setSttcLink(List<STTCLinkSTTS> sttcLink) {
        this.sttcLink = sttcLink;
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
