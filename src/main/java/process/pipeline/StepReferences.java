package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a step-engine pipeline's steps point at in other services, read from the Task Registry rather than from task
 * names: a step's config property whose schema says {@code format: prompt} names an ai-service prompt, one that says
 * {@code format: api-request} an integration-service API request. A new task that takes a prompt or a request is
 * counted the moment its schema says so.
 *
 * Who asks (console review 2026-10-07):
 * <ul>
 *   <li>"Run with a different AI model..." -- a pipeline's AI steps are its steps backed by ai-service that name a
 *       prompt ({@link #aiSteps}), as well as the old pipeline's AI fields;</li>
 *   <li>the prompts' "Used by" and the delete check ai-service makes before a prompt goes (countUsingPrompt);</li>
 *   <li>an API collection's "Used by" and integration-service's delete check (apiRequestUsers).</li>
 * </ul>
 * A legacy definition has no engine steps, so nothing here; its AI fields stay the pipeline table's.
 */
@Component
public class StepReferences {

    /** The schema format of a config property naming a saved ai-service prompt. */
    public static final String PROMPT = "prompt";

    /** The schema format of a config property naming a saved integration-service API request. */
    public static final String API_REQUEST = "api-request";

    private final Logger logger = LoggerFactory.getLogger(StepReferences.class);

    private final StepTasks tasks;

    public StepReferences(StepTasks tasks) {
        this.tasks = tasks;
    }

    /** One step's reference: the step, its task's registry entry, the property and the id it holds. */
    public static final class Ref {
        public final PipelineDefinition.Step step;
        public final TaskSpec spec;
        public final String property;
        public final long id;

        Ref(PipelineDefinition.Step step, TaskSpec spec, String property, long id) {
            this.step = step;
            this.spec = spec;
            this.property = property;
            this.id = id;
        }

        public String stepKey() {
            return this.step.getKey();
        }

        /** The step's name as its author gave it, else its key. */
        public String label() {
            String name = this.step.getName();
            return name == null || name.trim().isEmpty() ? this.step.getKey() : name.trim();
        }

        public Map<String, Object> config() {
            return this.step.effectiveConfig();
        }
    }

    /** Every reference of this format in the definition's steps, in step order (a step may hold several). */
    public List<Ref> of(PipelineDefinition definition, String format) {
        List<Ref> found = new ArrayList<>();
        if (definition == null || definition.isLegacy() || format == null) {
            return found;
        }
        for (PipelineDefinition.Step step : definition.getSteps()) {
            Optional<StepTask> task = this.tasks.find(step.getTask());
            if (!task.isPresent()) {
                continue;
            }
            TaskSpec spec = task.get().spec();
            for (String property : propertiesOfFormat(spec, format)) {
                Long id = idOf(step.effectiveConfig().get(property));
                if (id != null) {
                    found.add(new Ref(step, spec, property, id));
                }
            }
        }
        return found;
    }

    /** The AI steps: a step its registry entry backs with ai-service, naming a prompt -- the step whose model a run may choose. */
    public List<Ref> aiSteps(PipelineDefinition definition) {
        List<Ref> steps = new ArrayList<>();
        for (Ref ref : this.of(definition, PROMPT)) {
            if (TaskSpec.AI.equals(ref.spec.backingService()) && steps.stream().noneMatch(s -> s.stepKey().equals(ref.stepKey()))) {
                steps.add(ref);
            }
        }
        return steps;
    }

    /** A stored definition read for its references; one that cannot be read has none (and is logged, not thrown). */
    public Optional<PipelineDefinition> read(PipelineDefinitionStore.Stored stored) {
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(stored.definition());
        } catch (RuntimeException unreadable) {
            this.logger.warn("Pipeline definition {} is skipped while reading its references: {}", stored.id, unreadable.getMessage());
            return Optional.empty();
        }
    }

    /** Whether any of these definitions' references of this format hold one of these ids: the pipelines that use them. */
    public Map<Long, List<Ref>> using(Collection<PipelineDefinitionStore.Named> definitions, String format, Collection<Long> ids) {
        Map<Long, List<Ref>> byPipeline = new LinkedHashMap<>();
        for (PipelineDefinitionStore.Named named : definitions == null ? Collections.<PipelineDefinitionStore.Named>emptyList() : definitions) {
            Optional<PipelineDefinition> definition = this.read(named.stored);
            if (!definition.isPresent()) {
                continue;
            }
            for (Ref ref : this.of(definition.get(), format)) {
                if (ids.contains(ref.id)) {
                    byPipeline.computeIfAbsent(named.stored.pipelineKey, k -> new ArrayList<>()).add(ref);
                }
            }
        }
        return byPipeline;
    }

    /** The top-level config properties of a task whose schema has this format. */
    @SuppressWarnings("unchecked")
    static List<String> propertiesOfFormat(TaskSpec spec, String format) {
        List<String> names = new ArrayList<>();
        Map<String, Object> schema = spec == null ? null : spec.configSchema();
        Object properties = schema == null ? null : schema.get("properties");
        if (!(properties instanceof Map)) {
            return names;
        }
        for (Map.Entry<String, Object> property : ((Map<String, Object>) properties).entrySet()) {
            if (property.getValue() instanceof Map && format.equals(((Map<String, Object>) property.getValue()).get("format"))) {
                names.add(property.getKey());
            }
        }
        return names;
    }

    /** A positive whole number however the definition holds it (a JSON number, or digits as text); otherwise null. */
    static Long idOf(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            long l = ((Number) value).longValue();
            return l > 0 && d == l ? l : null;
        }
        if (value instanceof String && ((String) value).trim().matches("[1-9][0-9]{0,18}")) {
            return Long.valueOf(((String) value).trim());
        }
        return null;
    }
}
