package process.ai;

import process.model.dto.AiPromptDto;
import process.model.dto.ResponseDto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Everything Core asks the AI service (MIG-146, ADR-020). Prompts, model connections and prompt runs
 * are AI's; pipelines and their steps are Core's. So Core decides which steps a run has and writes the
 * answers into the task's document, and AI runs one step at a time -- idempotent per (run, step tag)
 * -- and answers what a prompt is. File chat and the job assistant ask by agent id: the provider key
 * never leaves the AI service.
 */
public interface AiPort {

    /** What a prompt is, for a pipeline's form checks and for the step Core hands to a worker. */
    final class PromptInfo {
        public Long promptId;
        public String promptUuid;
        public String name;
        public Integer version;
        public String status;
        public Long tenantId;
        public List<AiPromptDto.Variable> variables = new ArrayList<>();
    }

    /** How one server step went: the answer, or why there is none, and what it cost. */
    final class StepResult {
        public String status;
        public String output;
        public String error;
        public String promptName;
        public Integer promptVersion;
        public Integer latencyMs;
        public Integer tokensIn;
        public Integer tokensOut;
        public boolean reused;

        public boolean ok() {
            return "ok".equals(this.status);
        }

        /** A step that could not be run at all, said the way a refused step is. */
        public static StepResult failed(String why) {
            StepResult result = new StepResult();
            result.status = "failed";
            result.error = why;
            return result;
        }
    }

    /**
     * Runs one server step. Never throws: an AI service that cannot be reached is a failed step, so
     * the step's on-error rule (fail the run, or continue with it empty) decides what happens next.
     */
    StepResult runStep(Long tenantId, Long jobQueueId, String stepTag, Long promptId, Map<String, String> values);

    /** The prompts behind these ids, in one call; an id AI does not know is simply absent. */
    Map<Long, PromptInfo> prompts(Collection<Long> promptIds) throws AiUnavailableException;

    /** An agent's runtime configuration, as the signed-in caller may see it -- never its key. */
    ResponseDto runtimeConfig(Long aiAgentId) throws AiUnavailableException;

    /** One ad-hoc answer from an agent, as the signed-in caller. AI supplies the agent's own key. */
    ResponseDto adHoc(Long aiAgentId, String instructions, String text, Boolean jsonMode) throws AiUnavailableException;

    /** The AI service could not be asked. Callers refuse rather than guess. */
    class AiUnavailableException extends Exception {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
