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
            if ("worker".equals(step.getRunIn())) {
                // The worker runs this one: it goes into the document as an instruction, and the
                // worker asks aiPrompt.json/run with the values it resolved (a file's text, say).
                AiPort.PromptInfo prompt = handed.get(step.getPromptId());
                if (prompt == null || !"Active".equals(prompt.status) || !Objects.equals(prompt.tenantId, tenantId)) {
                    return new Outcome(null, String.format("AI step <%s> names a prompt that is no longer active in this workspace.", tag));
                }
                xml.addStep(prompt.promptUuid, prompt.version, tag, step.getOnError(), this.mapOf(step));
                outcome.notes.add(String.format("AI step <%s>: handed to the worker (%s v%d).", tag, prompt.name, prompt.version));
                continue;
            }
            AiPort.StepResult run = this.ai.runStep(tenantId, jobQueueId, tag, step.getPromptId(), this.valuesOf(step, xml));
            if (run.reused) {
                outcome.notes.add(String.format("AI step <%s>: reused the answer already recorded for this run.", tag));
            }
            if (run.ok()) {
                xml.set(tag, run.output);
                outcome.notes.add(String.format("AI step <%s>: %s v%s answered in %.1f s (%d in, %d out tokens).", tag,
                    run.promptName == null ? "prompt " + step.getPromptId() : run.promptName, run.promptVersion,
                    (run.latencyMs == null ? 0 : run.latencyMs) / 1000.0,
                    run.tokensIn == null ? 0 : run.tokensIn, run.tokensOut == null ? 0 : run.tokensOut));
            } else if ("continue".equals(step.getOnError())) {
                xml.set(tag, "");
                outcome.notes.add(String.format("AI step <%s> failed and the pipeline continues with it empty: %s", tag, run.error));
            } else {
                Outcome failed = new Outcome(null, String.format("AI step <%s> failed: %s", tag, run.error));
                failed.notes.addAll(outcome.notes);
                return failed;
            }
        }
        return copyNotes(new Outcome(xml.toString(), null), outcome);
    }

    private static Outcome copyNotes(Outcome into, Outcome from) { into.notes.addAll(from.notes); return into; }

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
