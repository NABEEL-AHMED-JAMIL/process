package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

import javax.persistence.*;

/** One field of a task form: the XML tag it fills, and how it is presented. */
/**
 * @author Nabeel Ahmed
 * */
@Entity
@Table(name = "task_form_field")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskFormField {

    @GenericGenerator(
        name = "taskFormFieldSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "task_form_source_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        })
    @Id
    @GeneratedValue(generator = "taskFormFieldSequenceGenerator")
    @Column(name = "task_form_field_id")
    private Long taskFormFieldId;

    // Ignored on the way out: the form already carries its fields, and serialising the parent
    // from each child is how a response becomes infinitely deep.
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_form_id", nullable = false)
    private TaskForm taskForm;

    @Column(name = "tag_key", nullable = false)
    private String tagKey;

    @Column(name = "tag_parent")
    private String tagParent;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "field_type", nullable = false)
    private String fieldType = "text";

    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "default_value")
    private String defaultValue;

    @Column(name = "help_text")
    private String helpText;

    @Column(name = "field_options")
    private String fieldOptions;

    @Column(name = "position", nullable = false)
    private int position;

    public Long getTaskFormFieldId() { return taskFormFieldId; }
    public void setTaskFormFieldId(Long taskFormFieldId) { this.taskFormFieldId = taskFormFieldId; }

    public TaskForm getTaskForm() { return taskForm; }
    public void setTaskForm(TaskForm taskForm) { this.taskForm = taskForm; }

    public String getTagKey() { return tagKey; }
    public void setTagKey(String tagKey) { this.tagKey = tagKey; }

    public String getTagParent() { return tagParent; }
    public void setTagParent(String tagParent) { this.tagParent = tagParent; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getFieldType() { return fieldType; }
    public void setFieldType(String fieldType) { this.fieldType = fieldType; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public String getHelpText() { return helpText; }
    public void setHelpText(String helpText) { this.helpText = helpText; }

    public String getFieldOptions() { return fieldOptions; }
    public void setFieldOptions(String fieldOptions) { this.fieldOptions = fieldOptions; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
