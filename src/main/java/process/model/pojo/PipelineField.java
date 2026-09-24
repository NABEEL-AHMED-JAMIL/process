package process.model.pojo;

import javax.persistence.PrePersist;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.Filter;
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
@Table(name = "pipeline_field")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class PipelineField {

    @GenericGenerator(
        name = "pipelineFieldSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "pipeline_source_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        })
    @Id
    @GeneratedValue(generator = "pipelineFieldSequenceGenerator")
    @Column(name = "pipeline_field_id")
    private Long pipelineFieldId;

    // Ignored on the way out: the form already carries its fields, and serialising the parent
    // from each child is how a response becomes infinitely deep.
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_key", nullable = false)
    private Pipeline pipeline;

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

    // An AI step (field_type "ai"): the prompt it runs, which earlier fields feed its variables
    // ({"promptVariable": "sourceTagKey"}), and what a failed call does to the run. The answer is
    // written to this field's own tag before dispatch, so the worker sees an ordinary tag.
    @Column(name = "prompt_id")
    private Long promptId;
    @Column(name = "variable_map", columnDefinition = "TEXT")
    private String variableMap;
    @Column(name = "on_error")
    private String onError;
    /** "server" (before dispatch, the default) or "worker" (the consumer runs it, and may read a file). */
    @Column(name = "run_in")
    private String runIn;
    /** The prompt's name, for the screens; not stored. */
    @Transient
    private String promptName;

    public Long getPromptId() { return promptId; }
    public void setPromptId(Long promptId) { this.promptId = promptId; }
    public String getVariableMap() { return variableMap; }
    public void setVariableMap(String variableMap) { this.variableMap = variableMap; }
    public String getOnError() { return onError; }
    public void setOnError(String onError) { this.onError = onError; }
    public String getRunIn() { return runIn; }
    public void setRunIn(String runIn) { this.runIn = runIn; }
    public String getPromptName() { return promptName; }
    public void setPromptName(String promptName) { this.promptName = promptName; }

    public Long getPipelineFieldId() { return pipelineFieldId; }
    public void setPipelineFieldId(Long pipelineFieldId) { this.pipelineFieldId = pipelineFieldId; }

    /**
     * The pipeline's tenant (V102, MIG-29/164): taken from the pipeline when the field is first written -- fields are
     * made in several places and all reach the database through the pipeline's cascade -- and kept equal to it by the
     * database (fk_pipeline_field_pipeline_tenant, ON UPDATE CASCADE), so never written again from here.
     */
    // Not on the wire: nothing a console sends or reads names it (the wire format is unchanged).
    @JsonIgnore
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @PrePersist
    protected void takeThePipelinesTenant() {
        if (this.tenantId == null && this.pipeline != null) {
            this.tenantId = this.pipeline.getTenantId();
        }
    }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Pipeline getPipeline() { return pipeline; }
    public void setPipeline(Pipeline pipeline) { this.pipeline = pipeline; }

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
