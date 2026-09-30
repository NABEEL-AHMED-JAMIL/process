package process.forms;

import java.util.List;

/** POST form.json/save's body: a new form (no formId) or the whole of an existing one. */
public class FormSaveRequest {

    private Long formId;
    private String name;
    private String description;
    private String status;
    private List<FormField> fields;
    private Long jobId;

    public FormSaveRequest() {
    }

    public FormSaveRequest(Long formId, String name, String description, String status, List<FormField> fields, Long jobId) {
        this.formId = formId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.fields = fields;
        this.jobId = jobId;
    }

    public Long getFormId() { return this.formId; }

    public void setFormId(Long formId) { this.formId = formId; }

    public String getName() { return this.name; }

    public void setName(String name) { this.name = name; }

    public String getDescription() { return this.description; }

    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return this.status; }

    public void setStatus(String status) { this.status = status; }

    public List<FormField> getFields() { return this.fields; }

    public void setFields(List<FormField> fields) { this.fields = fields; }

    public Long getJobId() { return this.jobId; }

    public void setJobId(Long jobId) { this.jobId = jobId; }
}
