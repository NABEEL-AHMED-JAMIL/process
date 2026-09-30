package process.forms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * One field of a form (Wave 5 Forms lite; MIG-277): its key (the answer's name, and the column a pipeline reads), its
 * label and help text, its type ({@link FormFields#TYPES}), whether it must be answered, and what its type needs -- a
 * choice's options, a table's columns and row limit, a file's accepted types, size and count, a lookup's source. Any
 * field may be shown only when an earlier answer says so (showWhen) and required only when one does (requiredWhen).
 * {@link FormFields} decides what a valid one is.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormField {

    private String key;
    private String label;
    private String type;
    private boolean required;
    private String help;
    private List<String> options;
    private Rule showWhen;
    private Rule requiredWhen;
    private List<FormField> columns;
    private Integer maxRows;
    private List<String> accept;
    private Integer maxSizeMb;
    private Integer maxFiles;
    private Lookup lookup;

    /** A condition on an earlier field's answer: eq, ne, in, gt, lt, filled, empty. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        private String field;
        private String op;
        private Object value;

        public Rule() {
        }

        public Rule(String field, String op, Object value) {
            this.field = field;
            this.op = op;
            this.value = value;
        }

        public String getField() { return this.field; }

        public void setField(String field) { this.field = field; }

        public String getOp() { return this.op; }

        public void setOp(String op) { this.op = op; }

        public Object getValue() { return this.value; }

        public void setValue(Object value) { this.value = value; }
    }

    /** Where a lookup field's values come from: the answers to a field of another form of the workspace. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Lookup {
        private Long formId;
        private String field;

        public Lookup() {
        }

        public Lookup(Long formId, String field) {
            this.formId = formId;
            this.field = field;
        }

        public Long getFormId() { return this.formId; }

        public void setFormId(Long formId) { this.formId = formId; }

        public String getField() { return this.field; }

        public void setField(String field) { this.field = field; }
    }

    public FormField() {
    }

    public FormField(String key, String label, String type, boolean required, String help, List<String> options) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.required = required;
        this.help = help;
        this.options = options == null ? null : new ArrayList<>(options);
    }

    public String getKey() { return this.key; }

    public void setKey(String key) { this.key = key; }

    public String getLabel() { return this.label; }

    public void setLabel(String label) { this.label = label; }

    public String getType() { return this.type; }

    public void setType(String type) { this.type = type; }

    public boolean isRequired() { return this.required; }

    public void setRequired(boolean required) { this.required = required; }

    public String getHelp() { return this.help; }

    public void setHelp(String help) { this.help = help; }

    public List<String> getOptions() { return this.options; }

    public void setOptions(List<String> options) { this.options = options; }

    public Rule getShowWhen() { return this.showWhen; }

    public void setShowWhen(Rule showWhen) { this.showWhen = showWhen; }

    public Rule getRequiredWhen() { return this.requiredWhen; }

    public void setRequiredWhen(Rule requiredWhen) { this.requiredWhen = requiredWhen; }

    public List<FormField> getColumns() { return this.columns; }

    public void setColumns(List<FormField> columns) { this.columns = columns; }

    public Integer getMaxRows() { return this.maxRows; }

    public void setMaxRows(Integer maxRows) { this.maxRows = maxRows; }

    public List<String> getAccept() { return this.accept; }

    public void setAccept(List<String> accept) { this.accept = accept; }

    public Integer getMaxSizeMb() { return this.maxSizeMb; }

    public void setMaxSizeMb(Integer maxSizeMb) { this.maxSizeMb = maxSizeMb; }

    public Integer getMaxFiles() { return this.maxFiles; }

    public void setMaxFiles(Integer maxFiles) { this.maxFiles = maxFiles; }

    public Lookup getLookup() { return this.lookup; }

    public void setLookup(Lookup lookup) { this.lookup = lookup; }
}
