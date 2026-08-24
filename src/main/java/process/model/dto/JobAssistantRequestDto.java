package process.model.dto;

import java.util.List;

/**
 * A question about one job, answered by a configured AI agent.
 *
 * The job's data is gathered server-side from jobId -- the caller sends no facts of its own,
 * so it cannot smuggle another job's details into the context.
 */
public class JobAssistantRequestDto {

    private Long jobId;
    private Long aiAgentId;
    private String message;
    /** Earlier turns, so a follow-up like "and the failures?" still makes sense. */
    private List<String> history;

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Long getAiAgentId() {
        return aiAgentId;
    }

    public void setAiAgentId(Long aiAgentId) {
        this.aiAgentId = aiAgentId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getHistory() {
        return history;
    }

    public void setHistory(List<String> history) {
        this.history = history;
    }

}
