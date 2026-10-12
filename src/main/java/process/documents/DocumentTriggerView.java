package process.documents;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A job's document trigger as the console reads it (sourceJob.json/documentTrigger). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentTriggerView {

    private final Long jobId;
    private final boolean configured;
    private final Boolean enabled;
    private final Long documentTypeId;
    private final Integer batchSize;
    private final String dateUpdated;
    private final int waiting;

    private DocumentTriggerView(Long jobId, boolean configured, Boolean enabled, Long documentTypeId, Integer batchSize, String dateUpdated,
        int waiting) {
        this.jobId = jobId;
        this.configured = configured;
        this.enabled = enabled;
        this.documentTypeId = documentTypeId;
        this.batchSize = batchSize;
        this.dateUpdated = dateUpdated;
        this.waiting = waiting;
    }

    static DocumentTriggerView of(Long jobId, DocumentTriggerStore.Trigger trigger, int waiting) {
        if (trigger == null) {
            return new DocumentTriggerView(jobId, false, null, null, null, null, waiting);
        }
        return new DocumentTriggerView(jobId, true, trigger.enabled, trigger.documentTypeId, trigger.batchSize,
            trigger.dateUpdated == null ? null : trigger.dateUpdated.toString(), waiting);
    }

    public Long getJobId() { return this.jobId; }

    public boolean isConfigured() { return this.configured; }

    public Boolean getEnabled() { return this.enabled; }

    /** The Document Intelligence type whose approved documents start the job. */
    public Long getDocumentTypeId() { return this.documentTypeId; }

    /** How many documents that waited for the job one run takes (1: a run per document). */
    public Integer getBatchSize() { return this.batchSize; }

    /** When the trigger was last set, an instant (UTC, ...Z). */
    public String getDateUpdated() { return this.dateUpdated; }

    /** How many approved documents wait for the job's next run now. */
    public int getWaiting() { return this.waiting; }
}
