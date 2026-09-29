package process.inbox;

/** POST sourceJob.json/inboxTrigger/save's body: which job, on or off, and which files (a glob on the name; blank for all). */
public class InboxTriggerRequest {

    private Long jobId;
    private Boolean enabled;
    private String filePattern;

    public InboxTriggerRequest() {
    }

    public InboxTriggerRequest(Long jobId, Boolean enabled, String filePattern) {
        this.jobId = jobId;
        this.enabled = enabled;
        this.filePattern = filePattern;
    }

    public Long getJobId() { return this.jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Boolean getEnabled() { return this.enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getFilePattern() { return this.filePattern; }
    public void setFilePattern(String filePattern) { this.filePattern = filePattern; }
}
