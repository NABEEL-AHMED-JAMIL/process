package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What one pipeline's payload looks like, so a task can be filled in rather than hand-written.
 *
 * Holds no payload of its own. A task's tags remain the only record of what it sends; this
 * describes what those tags mean. Delete a form and every task built with it keeps working.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "task_form")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class TaskForm implements Audited {
    @Transient
    private String updatedByName;

    @Column(name = "updated_by")
    private Long updatedBy;


    @GenericGenerator(
        name = "taskFormSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "task_form_source_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        })
    @Id
    @GeneratedValue(generator = "taskFormSequenceGenerator")
    @Column(name = "task_form_id")
    private Long taskFormId;

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "form_name", nullable = false)
    private String formName;

    @Column(name = "description")
    private String description;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_status", nullable = false)
    private Status formStatus = Status.Active;

    @Column(name = "date_created", nullable = false)
    private LocalDateTime dateCreated = LocalDateTime.now();

    @Column(name = "created_by")
    private Long createdBy;

    /** Resolved for display only -- created_by holds the id, and an id tells a reader nothing. */
    @Transient
    private String createdByName;

    /**
     * Loaded with the form and replaced wholesale on save.
     *
     * orphanRemoval because a field taken off a form has no meaning without it -- leaving the
     * row behind would resurrect the field the next time the form was read.
     */
    @OneToMany(mappedBy = "taskForm", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<TaskFormField> fields = new ArrayList<>();

    public Long getTaskFormId() { return taskFormId; }
    public void setTaskFormId(Long taskFormId) { this.taskFormId = taskFormId; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public String getFormName() { return formName; }
    public void setFormName(String formName) { this.formName = formName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Status getFormStatus() { return formStatus; }
    public void setFormStatus(Status formStatus) { this.formStatus = formStatus; }

    public LocalDateTime getDateCreated() { return dateCreated; }
    public void setDateCreated(LocalDateTime dateCreated) { this.dateCreated = dateCreated; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public List<TaskFormField> getFields() { return fields; }
    public void setFields(List<TaskFormField> fields) { this.fields = fields; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    @Override
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public Long getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }
}
