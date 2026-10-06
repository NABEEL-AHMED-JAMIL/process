package process.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

/**
 * One AI step of one attempt of a run, as run_ai_step keeps it (V182): what Core asked ai-service for -- the model
 * profile and whether the run or its schedule asked it -- and, for a server step, what ai-service answered it ran on.
 * The run's manifest; the Executions panel reads it, and a result made from the step copies the model from it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RunAiStep {

    public static final String SERVER = "server";

    public static final String WORKER = "worker";

    /** A worker step: written into the document for the worker, which asks ai-service itself. */
    public static final String HANDED = "handed";

    public static final String ANSWERED = "answered";

    public static final String FAILED = "failed";

    /** ai-service would not run the step on the model asked for (422): off the step's list, or the data policy's no. */
    public static final String REFUSED = "refused";

    public Long jobQueueId;
    public Integer attempt;
    public String stepKey;
    public String runIn;
    public Long promptId;
    public Integer promptVersion;
    public String modelProfile;
    public String profileSource;
    public String outcome;
    public String model;
    public Long connectionId;
    public Long modelOptionId;
    public String modelChoice;
    public boolean reused;
    public String error;
    public OffsetDateTime dateCreated;

    /** What a step asked for, before any answer. */
    static RunAiStep asked(Long jobQueueId, int attempt, String stepKey, String runIn, Long promptId, ModelProfiles.Asked asked) {
        RunAiStep step = new RunAiStep();
        step.jobQueueId = jobQueueId;
        step.attempt = attempt;
        step.stepKey = stepKey;
        step.runIn = runIn;
        step.promptId = promptId;
        step.modelProfile = asked.profile;
        step.profileSource = asked.source;
        return step;
    }

    /** The same step with ai-service's answer: what it ran on, or why it did not. */
    RunAiStep answered(AiPort.StepResult result) {
        this.outcome = result.refused ? REFUSED : result.ok() ? ANSWERED : FAILED;
        if (result.promptId != null) {
            this.promptId = result.promptId;
        }
        this.promptVersion = result.promptVersion;
        this.model = result.model;
        this.connectionId = result.connectionId;
        this.modelOptionId = result.modelOptionId;
        this.modelChoice = result.modelChoice;
        this.reused = result.reused;
        this.error = result.ok() ? null : result.error;
        return this;
    }
}
