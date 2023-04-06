package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
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

    @Column(name = "stts_order")
    private Long sttSOrder;

    @Column(name = "stts_status",nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sttf_id")
    private STTForm sstForm;

    @OneToMany(mappedBy="sstSttSection")
    private Set<STTControl> sttControlSet;

    public STTSection() {
    }

    public STTSection(String sttSName, Long sttSOrder,
        Status status, STTForm sstForm) {
        this.sttSName = sttSName;
        this.sttSOrder = sttSOrder;
        this.status = status;
        this.sstForm = sstForm;
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

    public Long getSttSOrder() {
        return sttSOrder;
    }

    public void setSttSOrder(Long sttSOrder) {
        this.sttSOrder = sttSOrder;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public STTForm getSstForm() {
        return sstForm;
    }

    public void setSstForm(STTForm sstForm) {
        this.sstForm = sstForm;
    }

    public Set<STTControl> getSttControlSet() {
        return sttControlSet;
    }

    public void setSttControlSet(Set<STTControl> sttControlSet) {
        this.sttControlSet = sttControlSet;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
