package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "dynamic_form_submission")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicFormSubmission {

    @GenericGenerator(
        name = "dynamicFormSubmissionSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "dynamic_form_submission_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "dynamic_form_submission_id")
    @GeneratedValue(generator = "dynamicFormSubmissionSequenceGenerator")
    private Long dynamicFormSubmissionId;

    @Column(name = "dynamic_form_id", nullable = false)
    private Long dynamicFormId;

    @Column(name = "uuid", unique = true)
    private String uuid;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    public DynamicFormSubmission() {}

    public Long getDynamicFormSubmissionId() {
        return dynamicFormSubmissionId;
    }

    public void setDynamicFormSubmissionId(Long dynamicFormSubmissionId) {
        this.dynamicFormSubmissionId = dynamicFormSubmissionId;
    }

    public Long getDynamicFormId() {
        return dynamicFormId;
    }

    public void setDynamicFormId(Long dynamicFormId) {
        this.dynamicFormId = dynamicFormId;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
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
