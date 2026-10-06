package process.forms;

/** POST form.json/status's body: which form, and Draft, Active or Archived. */
public class FormStatusRequest {

    private Long formId;
    private String status;

    public FormStatusRequest() {
    }

    public FormStatusRequest(Long formId, String status) {
        this.formId = formId;
        this.status = status;
    }

    public Long getFormId() { return this.formId; }

    public void setFormId(Long formId) { this.formId = formId; }

    public String getStatus() { return this.status; }

    public void setStatus(String status) { this.status = status; }
}
