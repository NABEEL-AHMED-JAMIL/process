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
        Map<String, String> map = step.getVariableMap() == null ? Collections.emptyMap()
            : this.gson.fromJson(step.getVariableMap(), new TypeToken<Map<String, String>>() {}.getType());
        for (Map.Entry<String, String> e : map.entrySet()) {
            String value = xml.get(e.getValue());
            job.values.put(e.getKey(), value == null ? "" : value);
        }
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
