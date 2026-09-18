package process.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.AiPromptDto;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPrompt;
import process.model.pojo.AiPromptRun;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.repository.AiModelConnectionRepository;
import process.model.repository.AiPromptRepository;
import process.model.repository.AiPromptRunRepository;
import process.model.repository.PipelineRepository;
import process.util.EncryptionUtil;
import java.util.*;

/**
 * Runs a pipeline's AI steps for one task before the run is dispatched, writing each answer
 * into the task's payload as an ordinary tag. The worker needs no change: it receives a
 * document with `<summary>…</summary>` filled in, the way it receives every other tag.
 *
 * Runs on the scheduler's thread, so nothing here reads TenantContext: the job's own
 * tenant scopes the prompt and the connection. Idempotent on (job queue, step tag): a queue
 * row dispatched twice reuses the stored answer rather than paying for it twice.
 */
@Service
public class AiStepService {

    private final Logger logger = LoggerFactory.getLogger(AiStepService.class);
    private final PipelineRepository pipelines;
    private final AiPromptRepository prompts;
    private final AiModelConnectionRepository connections;
    private final AiPromptRunRepository runs;
    private final EncryptionUtil encryptionUtil;
    private final PromptRunner runner;
    private final Gson gson = new Gson();

    public AiStepService(PipelineRepository pipelines, AiPromptRepository prompts, AiModelConnectionRepository connections,
        AiPromptRunRepository runs, EncryptionUtil encryptionUtil, PromptRunner runner) {
        this.pipelines = pipelines; this.prompts = prompts; this.connections = connections; this.runs = runs;
        this.encryptionUtil = encryptionUtil; this.runner = runner;
    }

    /** What happened: the payload to send (with the answers in), or why the run must fail. */
    public static class Outcome {
        public final String payload;
        public final String failure;
        public final List<String> notes = new ArrayList<>();
        public Outcome(String payload, String failure) { this.payload = payload; this.failure = failure; }
        public boolean failed() { return this.failure != null; }
    }

    /** The AI steps of a pipeline, in position order; empty when it has none. */
    public static List<PipelineField> stepsOf(Pipeline pipeline) {
        List<PipelineField> steps = new ArrayList<>();
        if (pipeline == null || pipeline.getFields() == null) return steps;
        for (PipelineField f : pipeline.getFields()) if ("ai".equals(f.getFieldType()) && f.getPromptId() != null) steps.add(f);
        steps.sort(Comparator.comparingInt(PipelineField::getPosition));
        return steps;
    }

    public Outcome apply(Long tenantId, String pipelineId, Long jobQueueId, String taskPayload) {
        if (pipelineId == null || pipelineId.trim().isEmpty()) return new Outcome(taskPayload, null);
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(pipelineId.trim(), tenantId, Status.Delete);
        List<PipelineField> steps = found.isEmpty() ? Collections.emptyList() : stepsOf(found.get(0));
        if (steps.isEmpty()) return new Outcome(taskPayload, null);
        PayloadXml xml;
        try { xml = PayloadXml.parse(taskPayload); }
        catch (Exception ex) { return new Outcome(taskPayload, "The task payload is not well-formed XML, so its AI step cannot read it: " + ex.getMessage()); }
        Outcome outcome = new Outcome(null, null);
        for (PipelineField step : steps) {
            String tag = step.getTagKey();
            if ("worker".equals(step.getRunIn())) {
                // The worker runs this one: it goes into the document as an instruction, and the
                // worker asks aiPrompt.json/run with the values it resolved (a file's text, say).
                AiPrompt prompt = this.prompts.findById(step.getPromptId()).orElse(null);
                if (prompt == null || prompt.getStatus() != Status.Active || !Objects.equals(prompt.getTenantId(), tenantId)) {
                    return new Outcome(null, String.format("AI step <%s> names a prompt that is no longer active in this workspace.", tag));
                }
                xml.addStep(prompt.getPromptUuid(), prompt.getVersion(), tag, step.getOnError(), this.mapOf(step));
                outcome.notes.add(String.format("AI step <%s>: handed to the worker (%s v%d).", tag, prompt.getName(), prompt.getVersion()));
                continue;
            }
            AiPromptRun run = this.runs.findByJobQueueIdAndStepTag(jobQueueId, tag).orElse(null);
            if (run == null || !"ok".equals(run.getStatus())) {
                // A failed attempt makes way for the retry: the step is unique per run.
                if (run != null) this.runs.delete(run);
                run = this.runStep(tenantId, jobQueueId, step, xml);
            } else {
                outcome.notes.add(String.format("AI step <%s>: reused the answer already recorded for this run.", tag));
            }
            if ("ok".equals(run.getStatus())) {
                xml.set(tag, run.getOutput());
                outcome.notes.add(String.format("AI step <%s>: %s v%s answered in %.1f s (%d in, %d out tokens).", tag,
                    this.promptName(step.getPromptId()), run.getPromptVersion(), (run.getLatencyMs() == null ? 0 : run.getLatencyMs()) / 1000.0,
                    run.getTokensIn() == null ? 0 : run.getTokensIn(), run.getTokensOut() == null ? 0 : run.getTokensOut()));
            } else if ("continue".equals(step.getOnError())) {
                xml.set(tag, "");
                outcome.notes.add(String.format("AI step <%s> failed and the pipeline continues with it empty: %s", tag, run.getError()));
            } else {
                Outcome failed = new Outcome(null, String.format("AI step <%s> failed: %s", tag, run.getError()));
                failed.notes.addAll(outcome.notes);
                return failed;
            }
        }
        return copyNotes(new Outcome(xml.toString(), null), outcome);
    }

    private static Outcome copyNotes(Outcome into, Outcome from) { into.notes.addAll(from.notes); return into; }

    private AiPromptRun runStep(Long tenantId, Long jobQueueId, PipelineField step, PayloadXml xml) {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, String> e : this.mapOf(step).entrySet()) {
            String value = xml.get(e.getValue());
            values.put(e.getKey(), value == null ? "" : value);
        }
        return this.runStepWithValues(tenantId, jobQueueId, step, values);
    }

    private Map<String, String> mapOf(PipelineField step) {
        return step.getVariableMap() == null || step.getVariableMap().trim().isEmpty() ? Collections.emptyMap()
            : this.gson.fromJson(step.getVariableMap(), new TypeToken<Map<String, String>>() {}.getType());
    }

    /**
     * A worker's call for a step it was handed: the same run as a server step, with the values
     * the worker resolved. The step has to be one the job's pipeline hands to the worker and
     * name this prompt; anything else is refused before any call. Idempotent per (run, tag).
     */
    public AiPromptRun runForWorker(Long tenantId, String pipelineId, Long jobQueueId, String stepTag,
        String promptUuid, Map<String, String> values) {
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(pipelineId == null ? "" : pipelineId.trim(), tenantId, Status.Delete);
        PipelineField step = found.isEmpty() ? null : stepsOf(found.get(0)).stream()
            .filter(f -> "worker".equals(f.getRunIn()) && f.getTagKey().equals(stepTag)).findFirst().orElse(null);
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = tenantId; job.kind = "run"; job.jobQueueId = jobQueueId; job.stepTag = stepTag;
        if (step == null) return this.refused(job, "This run's pipeline hands no such step to the worker.");
        job.promptId = step.getPromptId();
        AiPrompt prompt = this.prompts.findById(step.getPromptId()).orElse(null);
        if (prompt == null || !prompt.getPromptUuid().equals(promptUuid)) return this.refused(job, "The step does not name that prompt.");
        AiPromptRun existing = this.runs.findByJobQueueIdAndStepTag(jobQueueId, stepTag).orElse(null);
        if (existing != null && "ok".equals(existing.getStatus())) return existing;
        if (existing != null) this.runs.delete(existing);
        // The worker resolved the values (a file's contents, say); the document is not consulted.
        return this.runStepWithValues(tenantId, jobQueueId, step, values == null ? Collections.emptyMap() : values);
    }

    private AiPromptRun runStepWithValues(Long tenantId, Long jobQueueId, PipelineField step, Map<String, String> values) {
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = tenantId; job.kind = "run"; job.jobQueueId = jobQueueId; job.stepTag = step.getTagKey(); job.promptId = step.getPromptId();
        AiPrompt prompt = this.prompts.findById(step.getPromptId()).filter(p -> p.getStatus() == Status.Active && Objects.equals(p.getTenantId(), tenantId)).orElse(null);
        if (prompt == null) return this.refused(job, "The prompt this step names is no longer active in this workspace.");
        job.promptVersion = prompt.getVersion();
        AiModelConnection connection = (prompt.getConnectionId() != null
            ? this.connections.findById(prompt.getConnectionId()).filter(c -> c.getStatus() == Status.Active)
            : this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(tenantId, Status.Active)).orElse(null);
        if (connection == null) return this.refused(job, "No active model connection for this prompt: name one on it or set a workspace default.");
        job.connection = connection;
        job.apiKey = connection.getApiKey() == null ? null : this.encryptionUtil.decrypt(connection.getApiKey());
        job.model = prompt.getModel() == null || prompt.getModel().trim().isEmpty() ? connection.getDefaultModel() : prompt.getModel();
        job.system = prompt.getSystemInstructions(); job.template = prompt.getUserTemplate();
        job.variables = prompt.getVariables() == null ? new ArrayList<>() : this.gson.fromJson(prompt.getVariables(), new TypeToken<List<AiPromptDto.Variable>>() {}.getType());
        job.outputMode = prompt.getOutputMode(); job.outputSchema = prompt.getOutputSchema();
        job.temperature = prompt.getTemperature(); job.maxTokens = prompt.getMaxTokens();
        job.values.putAll(values);
        return this.runner.run(job);
    }

    /** A step that cannot even start still leaves a run row, so the history says why. */
    private AiPromptRun refused(PromptRunner.Job job, String why) {
        AiPromptRun row = new AiPromptRun();
        row.setTenantId(job.tenantId); row.setPromptId(job.promptId); row.setKind(job.kind); row.setJobQueueId(job.jobQueueId); row.setStepTag(job.stepTag);
        row.setStatus("failed"); row.setError(why); row.setLatencyMs(0);
        return this.runs.save(row);
    }

    private String promptName(Long promptId) {
        return this.prompts.findById(promptId).map(AiPrompt::getName).orElse("prompt " + promptId);
    }
}
