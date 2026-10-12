package process.documents;

/** POST sourceJob.json/documentTrigger/save's body: which job, which document type, on or off, how many waiting documents per run. */
public class DocumentTriggerRequest {

    private Long jobId;
    private Long documentTypeId;
    private Boolean enabled;
    /** How many waiting documents one run takes, 1..50; absent keeps the trigger's. */
    private Integer batchSize;

    public Long getJobId() { return this.jobId; }

    public void setJobId(Long jobId) { this.jobId = jobId; }

    public Long getDocumentTypeId() { return this.documentTypeId; }

    public void setDocumentTypeId(Long documentTypeId) { this.documentTypeId = documentTypeId; }

    public Boolean getEnabled() { return this.enabled; }

    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getBatchSize() { return this.batchSize; }

    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }
}
