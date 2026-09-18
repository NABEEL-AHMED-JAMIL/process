package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * One call to a model -- a Try it from the editor ("try") or a pipeline step ("run"). Holds
 * what was sent, what came back, the tokens and the time, so cost is a column. A pipeline
 * step is unique on (job_queue_id, step_tag): a retried worker gets the stored answer.
 */
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "ai_prompt_run")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiPromptRun {

    @GenericGenerator(name = "aiPromptRunSeq", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = { @Parameter(name = "sequence_name", value = "ai_prompt_run_seq"),
                       @Parameter(name = "initial_value", value = "1000"), @Parameter(name = "increment_size", value = "1") })
    @Id @GeneratedValue(generator = "aiPromptRunSeq")
    @Column(name = "run_id") private Long runId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "prompt_id") private Long promptId;
    @Column(name = "prompt_version") private Integer promptVersion;
    @Column(name = "connection_id") private Long connectionId;
    @Column(name = "kind", nullable = false) private String kind;
    @Column(name = "job_queue_id") private Long jobQueueId;
    @Column(name = "step_tag") private String stepTag;
    @Column(name = "rendered_input", columnDefinition = "TEXT") private String renderedInput;
    @Column(name = "output", columnDefinition = "TEXT") private String output;
    @Column(name = "tokens_in") private Integer tokensIn;
    @Column(name = "tokens_out") private Integer tokensOut;
    @Column(name = "latency_ms") private Integer latencyMs;
    @Column(name = "attempts", nullable = false) private Integer attempts = 1;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "error", columnDefinition = "TEXT") private String error;
    @Column(name = "date_created", nullable = false) private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());
    @Column(name = "created_by") private Long createdBy;

    public Long getRunId() { return runId; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public Long getPromptId() { return promptId; } public void setPromptId(Long v) { promptId = v; }
    public Integer getPromptVersion() { return promptVersion; } public void setPromptVersion(Integer v) { promptVersion = v; }
    public Long getConnectionId() { return connectionId; } public void setConnectionId(Long v) { connectionId = v; }
    public String getKind() { return kind; } public void setKind(String v) { kind = v; }
    public Long getJobQueueId() { return jobQueueId; } public void setJobQueueId(Long v) { jobQueueId = v; }
    public String getStepTag() { return stepTag; } public void setStepTag(String v) { stepTag = v; }
    public String getRenderedInput() { return renderedInput; } public void setRenderedInput(String v) { renderedInput = v; }
    public String getOutput() { return output; } public void setOutput(String v) { output = v; }
    public Integer getTokensIn() { return tokensIn; } public void setTokensIn(Integer v) { tokensIn = v; }
    public Integer getTokensOut() { return tokensOut; } public void setTokensOut(Integer v) { tokensOut = v; }
    public Integer getLatencyMs() { return latencyMs; } public void setLatencyMs(Integer v) { latencyMs = v; }
    public Integer getAttempts() { return attempts; } public void setAttempts(Integer v) { attempts = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getError() { return error; } public void setError(String v) { error = v; }
    public Timestamp getDateCreated() { return dateCreated; } public void setDateCreated(Timestamp v) { dateCreated = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
}
