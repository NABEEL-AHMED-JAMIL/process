package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "dynamic_form")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicForm {

    @GenericGenerator(
        name = "dynamicFormSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "dynamic_form_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "dynamic_form_id")
    @GeneratedValue(generator = "dynamicFormSequenceGenerator")
    private Long dynamicFormId;

    @Column(name = "form_name", nullable = false)
    private String formName;

    @Column(name = "description")
    private String description;

    @Column(name = "form_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    @JoinColumn(name = "dynamic_form_id")
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("fieldOrder asc")
    private List<DynamicFormField> fields = new ArrayList<>();

    public DynamicForm() {}

    public Long getDynamicFormId() {
        return dynamicFormId;
    }

    public void setDynamicFormId(Long dynamicFormId) {
        this.dynamicFormId = dynamicFormId;
    }

    public String getFormName() {
        return formName;
    }

    public void setFormName(String formName) {
        this.formName = formName;
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

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public List<DynamicFormField> getFields() {
        return fields;
    }

    public void setFields(List<DynamicFormField> fields) {
        this.fields = fields;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
