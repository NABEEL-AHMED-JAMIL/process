package process.forms;

import java.util.Map;

/** POST form.json/submit's body: which form, and the answers by field key. */
public class FormSubmitRequest {

    private Long formId;
    private Map<String, Object> answers;

    public FormSubmitRequest() {
    }

    public FormSubmitRequest(Long formId, Map<String, Object> answers) {
        this.formId = formId;
        this.answers = answers;
    }

    public Long getFormId() { return this.formId; }
    public void setFormId(Long formId) { this.formId = formId; }
    public Map<String, Object> getAnswers() { return this.answers; }
    public void setAnswers(Map<String, Object> answers) { this.answers = answers; }
}
