package process.ai;

import process.model.dto.AiPromptDto;
import process.model.dto.ResponseDto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

    /**
     * How one server step went: the answer, or why there is none, and what it cost -- and, since MIG-242, what it ran
     * on: the model, the connection, the allowed-list option that chose it and how it was chosen ("prompt", "default"
     * or "override"), with the prompt version it pinned.
     */
    final class StepResult {
        public String status;
        public String output;
        public String error;
        public Long promptId;
        public String promptName;
        public Integer promptVersion;
        public Integer latencyMs;
        public Integer tokensIn;
        public Integer tokensOut;
        public boolean reused;
        public String model;
        public Long connectionId;
        public Long modelOptionId;
        public String modelChoice;
        /**
         * ai-service would not run the step on the model asked for (HTTP 422: an option off the step's list, a default
         * on an inactive connection, or the data policy's no). Nothing was run and nothing will be by asking again,
         * so the run fails on it whatever the step's on-error rule says.
         */
        public boolean refused;

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
     *
     * @param modelProfile the model the run asks for (an ai-service model option id), or null for the step's default
     * @param sourceTaskId Core's pipeline (source task) the step belongs to, so ai-service uses that step's own allowed
     *                     list when it has one
     */
    StepResult runStep(Long tenantId, Long jobQueueId, String stepTag, Long promptId, Map<String, String> values,
        String modelProfile, Long sourceTaskId);

    /** One model an AI step may run on (ai-service's AiModelOptionDto): its id is what a run asks for. */
    final class ModelOption {
        public Long modelOptionId;
        public Long connectionId;
        public String connectionName;
        public String provider;
        public String model;
        public String effectiveModel;
        public Boolean isDefault;
        public Boolean connectionActive;
    }

    /**
     * The models this pipeline step may run on, in the step's workspace: the step's own list when it has one, else its
     * prompt's -- the list ai-service chooses from at run time. Empty when neither has one (the step runs as its prompt
     * says, and no model can be asked for).
     */
    List<ModelOption> stepModelOptions(Long tenantId, Long sourceTaskId, String stepKey, Long promptId) throws AiUnavailableException;

    /**
     * Replaces this pipeline step's own allowed list (empty: the step goes back to its prompt's). Core has decided the
     * caller may edit the step; ai-service checks each connection is the workspace's or the platform's, active, listed
     * once, with at most one default. Its answer is relayed as it came.
     */
    ResponseDto saveStepModelOptions(Long tenantId, Long sourceTaskId, String stepKey, Long promptId, List<ModelOption> options,
        Long updatedBy) throws AiUnavailableException;

    /** The prompts behind these ids, in one call; an id AI does not know is simply absent. */
    Map<Long, PromptInfo> prompts(Collection<Long> promptIds) throws AiUnavailableException;

    /** An agent's runtime configuration, as the signed-in caller may see it -- never its key. */
    ResponseDto runtimeConfig(Long aiAgentId) throws AiUnavailableException;

    /** One ad-hoc answer from an agent, as the signed-in caller. AI supplies the agent's own key. */
    ResponseDto adHoc(Long aiAgentId, String instructions, String text, Boolean jsonMode) throws AiUnavailableException;

    /**
     * MIG-243: the workspace's retention days per sensitivity level (public, internal, sensitive), as its data policy in
     * ai-service says them; a level with none is null or absent. Empty from a port that knows no policy.
     */
    default Map<String, Integer> retentionDays(Long tenantId) throws AiUnavailableException {
        return Collections.emptyMap();
    }

    /** The AI service could not be asked. Callers refuse rather than guess. */
    class AiUnavailableException extends Exception {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
