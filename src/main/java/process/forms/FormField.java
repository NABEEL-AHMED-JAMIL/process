package process.forms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * One field of a form (Wave 5 Forms lite): its key (the answer's name, and the column a pipeline reads), its label and
 * help text, its type -- text, longText, number, date, choice, yesNo, email -- whether it must be answered, and a
 * choice's options. {@link FormFields} decides what a valid one is.
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
}
