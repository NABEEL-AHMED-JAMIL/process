package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "stt_form")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTForm {

    @GenericGenerator(
        name = "sttFormSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "sttForm_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="sttf_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sttFormSequenceGenerator")
    private Long sttFId;

    @Column(name = "sttf_name", nullable = false)
    private String sttFName;

    @Column(name = "description", nullable = false)
    private String description;

    // status of job (active or disable or delete)
    @Column(name = "sttf_status",nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_task_type_id")
    private SourceTaskType sourceTaskType;

    @OneToMany(mappedBy="sstForm")
    private Set<STTSection> sttSectionSet;

    @Column(name = "date_created",
            nullable = false)
    private Timestamp dateCreated;

    public STTForm() {
    }

    public Long getSttFId() {
        return sttFId;
    }

    public void setSttFId(Long sttFId) {
        this.sttFId = sttFId;
    }

    public String getSttFName() {
        return sttFName;
    }

    public void setSttFName(String sttFName) {
        this.sttFName = sttFName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public SourceTaskType getSourceTaskType() {
        return sourceTaskType;
    }

    public void setSourceTaskType(SourceTaskType sourceTaskType) {
        this.sourceTaskType = sourceTaskType;
    }

    public Set<STTSection> getSttSectionSet() {
        return sttSectionSet;
    }

    public void setSttSectionSet(Set<STTSection> sttSectionSet) {
        this.sttSectionSet = sttSectionSet;
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
