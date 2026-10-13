package process.pipeline.review;

import java.util.List;

/**
 * Bulk review: POST sourceJob.json/review/bulkDecide {jobQueueIds, decision, comment, bulkId, dryRun} -- one decision
 * (APPROVED or REJECTED) and one comment for every run listed; a rejection's comment is its reason. {@code bulkId} is the
 * client's (^[A-Za-z0-9-]{8,64}$), made by the server when absent; {@code dryRun} checks and writes nothing.
 */
public class RunReviewBulkRequest {

    private List<Long> jobQueueIds;
    private String decision;
    private String comment;
    private String bulkId;
    private Boolean dryRun;

    public List<Long> getJobQueueIds() { return jobQueueIds; }

    public void setJobQueueIds(List<Long> jobQueueIds) { this.jobQueueIds = jobQueueIds; }

    public String getDecision() { return decision; }

    public void setDecision(String decision) { this.decision = decision; }

    public String getComment() { return comment; }

    public void setComment(String comment) { this.comment = comment; }

    public String getBulkId() { return bulkId; }

    public void setBulkId(String bulkId) { this.bulkId = bulkId; }

    public Boolean getDryRun() { return dryRun; }

    public void setDryRun(Boolean dryRun) { this.dryRun = dryRun; }
}
