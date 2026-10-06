package process.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.repository.PipelineRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Runs a pipeline's AI steps for one task before the run is dispatched, writing each answer into the
 * task's payload as an ordinary tag. The worker needs no change: it receives a document with
 * `<summary>…</summary>` filled in, the way it receives every other tag.
 *
 * Since ADR-020 this is Core's half only. The pipeline and its steps are Core's, so this decides which
 * steps a run has, reads their inputs from the document and writes their answers back. Running a step
 * is AI's: {@link AiPort#runStep} is idempotent on (job queue, step tag), so a queue row dispatched
 * twice reuses the stored answer rather than paying for it twice. A step handed to the worker goes
 * into the document as an instruction, with the prompt's uuid and version from AI's catalogue.
 *
 * Runs on the scheduler's thread, so nothing here reads TenantContext: the job's own tenant scopes
 * the prompt and the connection.
 *
 * Since MIG-242 each step asks for the model the run was started with ({@link ModelProfiles}: the run's
 * "Run with...", else its schedule's setting, else the step's default) and names the pipeline's source task,
 * so ai-service chooses from that step's own allowed list when it has one. What each step asked for and ran
 * on comes back in {@link Outcome#steps} for run_ai_step. A model ai-service refuses (422) fails the run
 * whatever the step's on-error rule says: asking again would change nothing, and continuing would hide it.
 */
@Service
public class AiStepService {

    private final Logger logger = LoggerFactory.getLogger(AiStepService.class);
    private final PipelineRepository pipelines;
    private final AiPort ai;
    private final Gson gson = new Gson();

    public AiStepService(PipelineRepository pipelines, AiPort ai) {
        this.pipelines = pipelines;
        this.ai = ai;
    }

    /**
     * What happened: the payload to send (with the answers in), or why the run must fail -- and each AI step as it
     * went, for run_ai_step: what model it asked for and, for a server step, what it ran on (MIG-242).
     */
    public static class Outcome {
        public final String payload;
        public final String failure;
        public final List<String> notes = new ArrayList<>();
        public final List<RunAiStep> steps = new ArrayList<>();

        public Outcome(String payload, String failure) {
            this.payload = payload;
            this.failure = failure;
        }

        public boolean failed() { return this.failure != null; }
    }

    /**
     * The run whose steps these are: its workspace, pipeline, queue row and attempt, the pipeline's source task (ai-service
     * reads that step's own allowed list by it) and the model each step asks for -- the run's "Run with...", else its
     * schedule's, else the step's default.
     */
    public static final class Run {
        public final Long tenantId;
        public final String pipelineId;
        public final Long jobQueueId;
        public final int attempt;
        public final Long sourceTaskId;
        public final ModelProfiles profiles;

        public Run(Long tenantId, String pipelineId, Long jobQueueId, int attempt, Long sourceTaskId, ModelProfiles profiles) {
            this.tenantId = tenantId;
            this.pipelineId = pipelineId;
            this.jobQueueId = jobQueueId;
            this.attempt = attempt;
            this.sourceTaskId = sourceTaskId;
            this.profiles = profiles == null ? ModelProfiles.NONE : profiles;
        }
    }

    /** The AI steps of a pipeline, in position order; empty when it has none. */
    public static List<PipelineField> stepsOf(Pipeline pipeline) {
        List<PipelineField> steps = new ArrayList<>();
        if (pipeline == null || pipeline.getFields() == null) return steps;
        for (PipelineField f : pipeline.getFields()) {
            if ("ai".equals(f.getFieldType()) && f.getPromptId() != null) {
                steps.add(f);
            }
        }
        steps.sort(Comparator.comparingInt(PipelineField::getPosition));
        return steps;
    }

    /**
     * Whether a run of this pipeline has AI steps at all, server or worker -- either kind asks the AI
     * service something before dispatch. Read from Core's own tables only; the pre-dispatch phase uses
     * it to keep runs with none off the AI threads (MIG-134).
     */
    public boolean hasSteps(Long tenantId, String pipelineId) {
        if (pipelineId == null || pipelineId.trim().isEmpty()) return false;
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(pipelineId.trim(), tenantId, Status.Delete);
        return !found.isEmpty() && !stepsOf(found.get(0)).isEmpty();
    }

    /** A run on every step's default model, of no source task: what a run was before MIG-242. */
    public Outcome apply(Long tenantId, String pipelineId, Long jobQueueId, String taskPayload) {
        return this.apply(new Run(tenantId, pipelineId, jobQueueId, 1, null, ModelProfiles.NONE), taskPayload);
    }

    public Outcome apply(Run run, String taskPayload) {
        Long tenantId = run.tenantId;
        String pipelineId = run.pipelineId;
        Long jobQueueId = run.jobQueueId;
        if (pipelineId == null || pipelineId.trim().isEmpty()) return new Outcome(taskPayload, null);
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(pipelineId.trim(), tenantId, Status.Delete);
        List<PipelineField> steps = found.isEmpty() ? Collections.emptyList() : stepsOf(found.get(0));
        if (steps.isEmpty()) return new Outcome(taskPayload, null);
        PayloadXml xml;
        try { xml = PayloadXml.parse(taskPayload); }
        catch (Exception ex) { return new Outcome(taskPayload, "The task payload is not well-formed XML, so its AI step cannot read it: " + ex.getMessage()); }
        Map<Long, AiPort.PromptInfo> handed;
        try {
            // One question for every worker step's prompt, however many there are.
            handed = this.ai.prompts(steps.stream().filter(s -> "worker".equals(s.getRunIn()))
                .map(PipelineField::getPromptId).collect(Collectors.toList()));
        } catch (AiPort.AiUnavailableException ex) {
            this.logger.warn("Run {}: {}", jobQueueId, ex.getMessage());
            return new Outcome(null, "The AI service could not be reached to prepare this run's AI steps.");
        }
        Outcome outcome = new Outcome(null, null);
        for (PipelineField step : steps) {
            String tag = step.getTagKey();
            ModelProfiles.Asked asked = run.profiles.forStep(tag);
            if ("worker".equals(step.getRunIn())) {
                // The worker runs this one: it goes into the document as an instruction, and the
                // worker asks aiPrompt.json/run with the values it resolved (a file's text, say).
                AiPort.PromptInfo prompt = handed.get(step.getPromptId());
                if (prompt == null || !"Active".equals(prompt.status) || !Objects.equals(prompt.tenantId, tenantId)) {
                    return new Outcome(null, String.format("AI step <%s> names a prompt that is no longer active in this workspace.", tag));
                }
                xml.addStep(prompt.promptUuid, prompt.version, tag, step.getOnError(), this.mapOf(step));
                // The worker asks ai-service itself; ai-service takes this step's model from Core's verify-callback,
                // which reads it back from this row -- so the worker runs on the model the run was started with.
                RunAiStep told = RunAiStep.asked(jobQueueId, run.attempt, tag, RunAiStep.WORKER, step.getPromptId(), asked);
                told.outcome = RunAiStep.HANDED;
                told.promptVersion = prompt.version;
                outcome.steps.add(told);
                outcome.notes.add(String.format("AI step <%s>: handed to the worker (%s v%d)%s.", tag, prompt.name, prompt.version,
                    asked.isDefault() ? "" : String.format(", asked to run on model option %s (%s)", asked.profile, asked.source)));
                continue;
            }
            AiPort.StepResult answer = this.ai.runStep(tenantId, jobQueueId, tag, step.getPromptId(), this.valuesOf(step, xml),
                asked.profile, run.sourceTaskId);
            outcome.steps.add(RunAiStep.asked(jobQueueId, run.attempt, tag, RunAiStep.SERVER, step.getPromptId(), asked).answered(answer));
            if (answer.refused) {
                // The model this run asked for (or the step's default) is not allowed. Continuing without the step would
                // hide that; retrying cannot change it. The run fails with ai-service's reason, whatever on-error says.
                Outcome failed = new Outcome(null, String.format("AI step <%s> could not run on %s: %s", tag,
                    asked.isDefault() ? "its default model" : String.format("model option %s (asked by the %s)", asked.profile, asked.source),
                    answer.error));
                failed.notes.addAll(outcome.notes);
                failed.steps.addAll(outcome.steps);
                return failed;
            }
            if (answer.reused) {
                outcome.notes.add(String.format("AI step <%s>: reused the answer already recorded for this run.", tag));
            }
            if (answer.ok()) {
                xml.set(tag, answer.output);
                outcome.notes.add(String.format("AI step <%s>: %s v%s answered in %.1f s (%d in, %d out tokens)%s.", tag,
                    answer.promptName == null ? "prompt " + step.getPromptId() : answer.promptName, answer.promptVersion,
                    (answer.latencyMs == null ? 0 : answer.latencyMs) / 1000.0,
                    answer.tokensIn == null ? 0 : answer.tokensIn, answer.tokensOut == null ? 0 : answer.tokensOut, ranOn(answer)));
            } else if ("continue".equals(step.getOnError())) {
                xml.set(tag, "");
                outcome.notes.add(String.format("AI step <%s> failed and the pipeline continues with it empty: %s", tag, answer.error));
            } else {
                Outcome failed = new Outcome(null, String.format("AI step <%s> failed: %s", tag, answer.error));
                failed.notes.addAll(outcome.notes);
                failed.steps.addAll(outcome.steps);
                return failed;
            }
        }
        return copyNotes(new Outcome(xml.toString(), null), outcome);
    }

    private static Outcome copyNotes(Outcome into, Outcome from) {
        into.notes.addAll(from.notes);
        into.steps.addAll(from.steps);
        return into;
    }

    /** ", on <model> (<how it was chosen>)" when ai-service said what the step ran on; nothing when it did not. */
    private static String ranOn(AiPort.StepResult answer) {
        if (answer.model == null) {
            return "";
        }
        return answer.modelChoice == null ? String.format(", on %s", answer.model) : String.format(", on %s (%s)", answer.model, answer.modelChoice);
    }

    /** The step's variables, read from the document by the tags its map names. */
    private Map<String, String> valuesOf(PipelineField step, PayloadXml xml) {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, String> e : this.mapOf(step).entrySet()) {
            String value = xml.get(e.getValue());
            values.put(e.getKey(), value == null ? "" : value);
        }
        return values;
    }

    private Map<String, String> mapOf(PipelineField step) {
        return step.getVariableMap() == null || step.getVariableMap().trim().isEmpty() ? Collections.emptyMap()
            : this.gson.fromJson(step.getVariableMap(), new TypeToken<Map<String, String>>() {}.getType());
    }
}
