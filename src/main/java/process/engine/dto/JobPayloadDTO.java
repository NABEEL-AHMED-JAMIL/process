package process.engine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.GsonBuilder;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobPayloadDTO {

    private Long jobQueueId;
    private Long jobId;
    private String taskPayload;
    private String homePageId;
    private String pipelineId;
    private Integer priority;
    /**
     * The worker's proof for this run: echo it as X-Worker-Token on every callback for this
     * jobQueueId. Good for this run and attempt only; a retry arrives with a new one.
     */
    private String callbackToken;
    private Integer attempt;
    /**
     * The id this run's dispatch was logged under (MIG-95). A worker should echo it as X-Correlation-Id
     * on its callbacks; one that does not is still logged under it, resolved from the run.
     */
    private String correlationId;
    /**
     * The run's workspace, source_job.tenant_id (MIG-201; worker-runtime contract section 2). A claim for the
     * worker to route and log by before it asks Core: the worker still proves it with verifyRun, and refuses a
     * run whose dispatch names another workspace. Absent for a job with no workspace.
     */
    private Long tenantId;

    public JobPayloadDTO() {
    }

    public Long getJobQueueId() {
        return jobQueueId;
    }

    public void setJobQueueId(Long jobQueueId) {
        this.jobQueueId = jobQueueId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getTaskPayload() {
        return taskPayload;
    }

    public void setTaskPayload(String taskPayload) {
        this.taskPayload = taskPayload;
    }

    public String getHomePageId() {
        return homePageId;
    }

    public void setHomePageId(String homePageId) {
        this.homePageId = homePageId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    @Override
    public String toString() {
        return new GsonBuilder().disableHtmlEscaping().create().toJson(this);
    }

    public String getCallbackToken() { return callbackToken; }
    public void setCallbackToken(String callbackToken) { this.callbackToken = callbackToken; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
}
