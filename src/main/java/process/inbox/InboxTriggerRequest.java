package process.inbox;

/**
 * POST sourceJob.json/inboxTrigger/save's body: which job, on or off, which files (a glob on the name; blank for all) and
 * how many files that waited one run takes (MIG-360).
 */
public class InboxTriggerRequest {

    private Long jobId;
    private Boolean enabled;
    private String filePattern;
    /** MIG-360: how many waiting files one run takes, 1..50; absent keeps the trigger's. */
    private Integer batchSize;

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

    public Integer getBatchSize() { return this.batchSize; }

    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }
}
