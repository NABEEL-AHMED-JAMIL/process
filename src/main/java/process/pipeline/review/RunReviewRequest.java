package process.pipeline.review;

/**
 * A review decision on a run's results (MIG-237): POST sourceJob.json/review/decide in the console. {@code decision}
 * is APPROVED or REJECTED; a rejection says why ({@code reason}) and may ask for the job to be run again
 * ({@code rerun}). {@code party} may be left out -- the console records the internal review -- and when given must be
 * "internal": the customer's review comes through the customer's own endpoint (POST /v1/runs/{id}/review), never this one.
 */
public class RunReviewRequest {

    private Long jobQueueId;
    private String decision;
    private String comment;
    private String reason;
    private Boolean rerun;
    private String party;

    public Long getJobQueueId() { return jobQueueId; }

    public void setJobQueueId(Long jobQueueId) { this.jobQueueId = jobQueueId; }

    public String getDecision() { return decision; }

    public void setDecision(String decision) { this.decision = decision; }

    public String getComment() { return comment; }

    public void setComment(String comment) { this.comment = comment; }

    public String getReason() { return reason; }

    public void setReason(String reason) { this.reason = reason; }

    public Boolean getRerun() { return rerun; }

    public void setRerun(Boolean rerun) { this.rerun = rerun; }

    public String getParty() { return party; }

    public void setParty(String party) { this.party = party; }
}
