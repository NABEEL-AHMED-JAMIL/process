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
@Table(name = "sttf_link_stt")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTFLinkSTT {

    @GenericGenerator(
        name = "auSttfSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "sequence_name", value = "au_sttf_Seq"),
            @org.hibernate.annotations.Parameter(name = "initial_value", value = "1000"),
            @org.hibernate.annotations.Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "au_sttf_id")
    @GeneratedValue(generator = "auSttfSequenceGenerator")
    private Long auSttfId;

    @Column(name = "sttf_order", nullable = false)
    private Long sttfOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sttf_id", nullable = false)
    private STTForm sttf;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stt_id", nullable = false)
    private STT stt;

    @OneToMany(mappedBy="sttf")
    private List<STTSLinkSTTF> sttsLink = new ArrayList<>();

    @Column(name = "status", nullable = false)
    private Long status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public STTFLinkSTT() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getAuSttfId() {
        return auSttfId;
    }

    public void setAuSttfId(Long auSttfId) {
        this.auSttfId = auSttfId;
    }

    public Long getSttfOrder() {
        return sttfOrder;
    }

    public void setSttfOrder(Long sttfOrder) {
        this.sttfOrder = sttfOrder;
    }

    public STTForm getSttf() {
        return sttf;
    }

    public void setSttf(STTForm sttf) {
        this.sttf = sttf;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public STT getStt() {
        return stt;
    }

    public void setStt(STT stt) {
        this.stt = stt;
    }

    public List<STTSLinkSTTF> getSttsLink() {
        return sttsLink;
    }

    public void setSttsLink(List<STTSLinkSTTF> sttsLink) {
        this.sttsLink = sttsLink;
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
