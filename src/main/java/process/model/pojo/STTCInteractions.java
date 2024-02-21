package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "sttc_interaction")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTCInteractions {

    @GenericGenerator(
        name = "sttcInteractionsSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "sttc_interactions_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="interactions_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sttcInteractionsSequenceGenerator")
    private Long interactionsId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "au_stts_id", nullable = false)
    private STTSLinkSTTF sttsLinkSTTF;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sttc_id", nullable = false)
    private STTControl sttc;

    @Column(name = "disabled_pattern")
    private String disabledPattern;

    @Column(name = "visible_pattern")
    private String visiblePattern;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(name = "status", nullable = false)
    private Long status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public STTCInteractions() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getInteractionsId() {
        return interactionsId;
    }

    public void setInteractionsId(Long interactionsId) {
        this.interactionsId = interactionsId;
    }

    public STTSLinkSTTF getSttsLinkSTTF() {
        return sttsLinkSTTF;
    }

    public void setSttsLinkSTTF(STTSLinkSTTF sttsLinkSTTF) {
        this.sttsLinkSTTF = sttsLinkSTTF;
    }

    public STTControl getSttc() {
        return sttc;
    }

    public void setSttc(STTControl sttc) {
        this.sttc = sttc;
    }

    public String getDisabledPattern() {
        return disabledPattern;
    }

    public void setDisabledPattern(String disabledPattern) {
        this.disabledPattern = disabledPattern;
    }

    public String getVisiblePattern() {
        return visiblePattern;
    }

    public void setVisiblePattern(String visiblePattern) {
        this.visiblePattern = visiblePattern;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
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
}
