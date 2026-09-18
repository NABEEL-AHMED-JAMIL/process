package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/** What a worker sends to run one AI step it was handed in the task's document. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiWorkerRunDto {
    private Long jobId;
    private Long jobQueueId;
    private String promptUuid;
    private Integer version;
    private String stepTag;
    private Map<String, String> variables;

    public Long getJobId() { return jobId; } public void setJobId(Long v) { jobId = v; }
    public Long getJobQueueId() { return jobQueueId; } public void setJobQueueId(Long v) { jobQueueId = v; }
    public String getPromptUuid() { return promptUuid; } public void setPromptUuid(String v) { promptUuid = v; }
    public Integer getVersion() { return version; } public void setVersion(Integer v) { version = v; }
    public String getStepTag() { return stepTag; } public void setStepTag(String v) { stepTag = v; }
    public Map<String, String> getVariables() { return variables; } public void setVariables(Map<String, String> v) { variables = v; }
}
