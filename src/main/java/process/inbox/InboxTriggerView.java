package process.inbox;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A job's inbox trigger as the console reads it (sourceJob.json/inboxTrigger). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InboxTriggerView {

    private final Long jobId;
    private final boolean configured;
    private final Boolean enabled;
    private final String filePattern;
    private final String dateUpdated;
    private final Integer batchSize;
    private final int waiting;

    private InboxTriggerView(Long jobId, boolean configured, Boolean enabled, String filePattern, String dateUpdated, Integer batchSize,
        int waiting) {
        this.jobId = jobId;
        this.configured = configured;
        this.enabled = enabled;
        this.filePattern = filePattern;
        this.dateUpdated = dateUpdated;
        this.batchSize = batchSize;
        this.waiting = waiting;
    }

    static InboxTriggerView of(Long jobId, InboxTriggerStore.Trigger trigger) {
        return of(jobId, trigger, 0);
    }

    static InboxTriggerView of(Long jobId, InboxTriggerStore.Trigger trigger, int waiting) {
        if (trigger == null) {
            return new InboxTriggerView(jobId, false, null, null, null, null, waiting);
        }
        return new InboxTriggerView(jobId, true, trigger.enabled, trigger.filePattern,
            trigger.dateUpdated == null ? null : trigger.dateUpdated.toString(), trigger.batchSize, waiting);
    }

    public Long getJobId() { return this.jobId; }

    public boolean isConfigured() { return this.configured; }

    public Boolean getEnabled() { return this.enabled; }

    /** A glob on the arriving file's name; absent for every file. */
    public String getFilePattern() { return this.filePattern; }

    /** When the trigger was last set, an instant (UTC, ...Z). */
    public String getDateUpdated() { return this.dateUpdated; }

    /** MIG-360: how many files that waited for the job one run takes (1: a run per file). */
    public Integer getBatchSize() { return this.batchSize; }

    /** MIG-360: how many files wait for the job's next run now. */
    public int getWaiting() { return this.waiting; }
}
