package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.repository.PipelineRepository;
import process.pipeline.registry.TaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.registry.TaskSpec;
import process.pipeline.tasks.MeasureImageStepTask;
import process.security.TenantContext;
import process.security.TenantOwnership;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A pipeline's definition as ordered steps, for the console's step builder (MIG-230, MIG-249): read it (the stored
 * latest, or the legacy wrap every pipeline without one has), check a draft, save it as the next version, and list
 * the tasks a step may run. A pipeline is the caller's workspace's or not found -- the same rule, and the same words,
 * as the pipeline's own form (PipelineServiceImpl.fieldsFor).
 *
 * MIG-231: the task list is the Task Registry as the caller's workspace sees it -- every entry with its config schema
 * and whether it is enabled here -- and one Legacy entry per pipeline of the workspace. A draft is checked in the
 * caller's workspace for the caller's role: a disabled or unavailable task, or one above the caller's role, is a
 * problem at its step. A workspace admin switches an overridable task on or off for the workspace.
 */
@Service
public class PipelineDefinitionService {

    static final String PIPELINE_NOT_FOUND = "That pipeline no longer exists.";

    private final Logger logger = LoggerFactory.getLogger(PipelineDefinitionService.class);

    private final PipelineRepository pipelines;
    private final PipelineDefinitionStore store;
    private final DefinitionValidator validator;
    private final TaskRegistry registry;
    private final TaskOverrideStore overrides;
    private final UserNameResolver names;

    public PipelineDefinitionService(PipelineRepository pipelines, PipelineDefinitionStore store, DefinitionValidator validator,
        TaskRegistry registry, TaskOverrideStore overrides, UserNameResolver names) {
        this.pipelines = pipelines;
        this.store = store;
        this.validator = validator;
        this.registry = registry;
        this.overrides = overrides;
        this.names = names;
    }

    /** A draft to check or save: the definition's text, as JSON or YAML ("json", "yaml", or null to tell by its shape). */
    public static class DefinitionRequest {
        private Long pipelineKey;
        private String format;
        private String text;

        public Long getPipelineKey() { return pipelineKey; }

        public void setPipelineKey(Long pipelineKey) { this.pipelineKey = pipelineKey; }

        public String getFormat() { return format; }

        public void setFormat(String format) { this.format = format; }

        public String getText() { return text; }

        public void setText(String text) { this.text = text; }
    }

    /** The pipeline's definition: its latest saved version, or the legacy wrap -- as the object, JSON and YAML. */
    public ResponseDto read(Long pipelineKey) {
        Optional<Pipeline> pipeline = this.owned(pipelineKey);
        if (!pipeline.isPresent()) {
            return new ResponseDto(ERROR, PIPELINE_NOT_FOUND);
        }
        Optional<PipelineDefinitionStore.Stored> latest = this.store.latest(pipeline.get().getPipelineKey());
        PipelineDefinition definition = latest.map(PipelineDefinitionStore.Stored::definition)
            .orElseGet(() -> PipelineDefinition.legacy(pipeline.get().getPipelineId()));
        Map<String, Object> payload = views(definition);
        payload.put("pipelineKey", pipeline.get().getPipelineKey());
        payload.put("pipelineId", pipeline.get().getPipelineId());
        payload.put("stored", latest.isPresent());
        payload.put("version", latest.map(stored -> stored.version).orElse(null));
        List<PipelineDefinitionStore.Stored> versions = this.store.versions(pipeline.get().getPipelineKey());
        List<Long> savers = versions.stream().map(stored -> stored.createdBy).filter(Objects::nonNull).distinct()
            .collect(Collectors.toList());
        Map<Long, String> byId = savers.isEmpty() ? Collections.emptyMap() : this.names.namesFor(savers);
        payload.put("versions", versions.stream().map(stored -> {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("version", stored.version);
            version.put("pipelineDefinitionId", stored.id);
            version.put("createdBy", stored.createdBy);
            version.put("createdByName", stored.createdBy == null ? null : byId.get(stored.createdBy));
            version.put("dateCreated", stored.dateCreated == null ? null : stored.dateCreated.toString());
            return version;
        }).collect(Collectors.toList()));
        return new ResponseDto(SUCCESS, latest.isPresent()
            ? String.format("Pipeline definition version %d.", latest.get().version)
            : "No definition is saved: the pipeline runs as its legacy step.", payload);
    }

    /** Whether a draft would save, with every problem at its path; the draft's JSON and YAML when it reads. */
    public ResponseDto validate(DefinitionRequest request) {
        if (request == null || request.getText() == null) {
            return new ResponseDto(ERROR, "The definition's text is required.");
        }
        PipelineDefinition definition;
        try {
            definition = DefinitionCodec.read(request.getText(), request.getFormat());
        } catch (DefinitionException ex) {
            return invalid(ex.getProblems(), null);
        }
        List<DefinitionProblem> problems = this.validator.problems(definition, TenantContext.getTenantId(), TenantContext.getUserRole());
        if (!problems.isEmpty()) {
            return invalid(problems, definition);
        }
        Map<String, Object> payload = views(definition);
        payload.put("valid", true);
        payload.put("problems", new ArrayList<>());
        return new ResponseDto(SUCCESS, "The definition is valid.", payload);
    }

    /**
     * Saves a draft as the pipeline's next version, when it validates and differs from the latest; the stored text is
     * the canonical JSON, whichever format it came in.
     */
    public ResponseDto save(DefinitionRequest request) {
        if (request == null || request.getText() == null) {
            return new ResponseDto(ERROR, "The definition's text is required.");
        }
        Optional<Pipeline> pipeline = this.owned(request.getPipelineKey());
        if (!pipeline.isPresent()) {
            return new ResponseDto(ERROR, PIPELINE_NOT_FOUND);
        }
        if (pipeline.get().getTenantId() == null) {
            // A platform pipeline has no workspace to keep a definition in (pipeline_definition.tenant_id is NOT NULL).
            return new ResponseDto(ERROR, "A platform pipeline keeps its legacy definition.");
        }
        PipelineDefinition definition;
        try {
            definition = this.validator.require(DefinitionCodec.read(request.getText(), request.getFormat()), pipeline.get().getTenantId(),
                TenantContext.getUserRole());
        } catch (DefinitionException ex) {
            return invalid(ex.getProblems(), null);
        }
        Optional<PipelineDefinitionStore.Stored> latest = this.store.latest(pipeline.get().getPipelineKey());
        // A measure step's target is written into every save; one the previous version had without it keeps red_on_skin.
        MeasureImageStepTask.pinTargets(definition.getSteps(), previousSteps(latest, definition));
        String json = DefinitionCodec.toJson(definition);
        if (latest.isPresent() && latest.get().json.equals(json)) {
            Map<String, Object> payload = views(definition);
            payload.put("version", latest.get().version);
            return new ResponseDto(SUCCESS, String.format("Unchanged: version %d is this definition.", latest.get().version), payload);
        }
        if (!latest.isPresent() && definition.isLegacy()) {
            Map<String, Object> payload = views(definition);
            payload.put("version", null);
            return new ResponseDto(SUCCESS, "Unchanged: the pipeline already runs as its legacy step.", payload);
        }
        PipelineDefinitionStore.Stored saved;
        try {
            saved = this.store.save(pipeline.get().getPipelineKey(), json, TenantContext.getAppUserId());
        } catch (DuplicateKeyException ex) {
            return new ResponseDto(ERROR, "Someone else saved this pipeline's definition at the same moment; open it again and retry.");
        }
        this.logger.info("Pipeline {} definition version {} saved ({} step(s)).", pipeline.get().getPipelineKey(), saved.version,
            definition.getSteps().size());
        Map<String, Object> payload = views(definition);
        payload.put("version", saved.version);
        payload.put("pipelineDefinitionId", saved.id);
        return new ResponseDto(SUCCESS, String.format("Saved as version %d.", saved.version), payload);
    }

    /** The previous version's steps; when it cannot be read, the draft's own, so no step loses the rule it had. */
    private static List<PipelineDefinition.Step> previousSteps(Optional<PipelineDefinitionStore.Stored> latest, PipelineDefinition draft) {
        if (!latest.isPresent()) {
            return null;
        }
        try {
            return latest.get().definition().getSteps();
        } catch (IllegalStateException unreadable) {
            return draft.getSteps();
        }
    }

    /** A workspace admin's switch on one task: on, off, or (null) back to its default. */
    public static class TaskSwitchRequest {
        private String code;
        private Boolean enabled;

        public String getCode() { return code; }

        public void setCode(String code) { this.code = code; }

        public Boolean getEnabled() { return enabled; }

        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    /**
     * The Task Registry as the caller's workspace sees it, for "Add step" and the step's form: every registered task's
     * entry (its config schema, enabled here or why not), then one Legacy entry per pipeline of the workspace -- the
     * {@code legacy} entry with that pipeline's pipelineId, pipelineKey and name, and the config a step runs it with.
     */
    public ResponseDto tasks() {
        Long tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> list = new ArrayList<>(this.registry.entries(tenantId));
        Optional<Map<String, Object>> legacy = list.stream().filter(entry -> PipelineDefinition.LEGACY_TASK.equals(entry.get("code"))).findFirst();
        int pipelines = 0;
        if (tenantId != null && legacy.isPresent()) {
            for (Pipeline pipeline : this.pipelines.findAllByTenantIdAndStatusNotOrderByPipelineKeyDesc(tenantId, Status.Delete)) {
                Map<String, Object> entry = new LinkedHashMap<>(legacy.get());
                entry.put("name", "Legacy: " + pipeline.getPipelineName());
                entry.put("pipelineKey", pipeline.getPipelineKey());
                entry.put("pipelineId", pipeline.getPipelineId());
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("pipelineId", pipeline.getPipelineId());
                entry.put("config", config);
                list.add(entry);
                pipelines++;
            }
        }
        return new ResponseDto(SUCCESS, String.format("%d step task(s) and %d legacy pipeline(s).", list.size() - pipelines, pipelines), list);
    }

    /**
     * Switches a task on or off for the caller's workspace (tenant admins), or back to its default. Legacy is never
     * switched (every existing pipeline stays runnable), and a task that is not available here cannot be switched on.
     * Switching a task off does not change a saved definition: a run whose definition names it is declined until it is
     * on again.
     */
    public ResponseDto switchTask(TaskSwitchRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ERROR, "Tasks are switched in a workspace; this caller has none.");
        }
        if (request == null || request.getCode() == null || request.getCode().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Which task to switch is required.");
        }
        Optional<StepTask> task = this.registry.tasks().find(request.getCode());
        if (!task.isPresent()) {
            return new ResponseDto(ERROR, String.format("No task '%s' is registered.", request.getCode().trim()));
        }
        TaskSpec spec = task.get().spec();
        if (!spec.overridable()) {
            return new ResponseDto(ERROR, String.format("'%s' cannot be switched: existing pipelines always stay runnable.", spec.code()));
        }
        if (Boolean.TRUE.equals(request.getEnabled()) && task.get().unavailable().isPresent()) {
            return new ResponseDto(ERROR, String.format("'%s' is not available here: %s.", spec.code(), task.get().unavailable().get()));
        }
        if (request.getEnabled() == null) {
            this.overrides.clear(tenantId, spec.code());
        } else {
            this.overrides.set(tenantId, spec.code(), request.getEnabled(), TenantContext.getAppUserId());
        }
        this.logger.info("Task {} switched {} in workspace {}.", spec.code(), request.getEnabled() == null ? "to its default"
            : request.getEnabled() ? "on" : "off", tenantId);
        Map<String, Object> entry = this.registry.entries(tenantId).stream().filter(one -> spec.code().equals(one.get("code")))
            .findFirst().orElse(null);
        return new ResponseDto(SUCCESS, request.getEnabled() == null ? String.format("'%s' is back to its default.", spec.code())
            : String.format("'%s' is switched %s in this workspace.", spec.code(), request.getEnabled() ? "on" : "off"), entry);
    }

    private Optional<Pipeline> owned(Long pipelineKey) {
        if (pipelineKey == null) {
            return Optional.empty();
        }
        return this.pipelines.findByPipelineKeyAndStatusNot(pipelineKey, Status.Delete)
            .filter(pipeline -> TenantOwnership.isOwnedByCaller(pipeline.getTenantId()));
    }

    private static Map<String, Object> views(PipelineDefinition definition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("legacy", definition.isLegacy());
        payload.put("definition", definition);
        payload.put("json", DefinitionCodec.toPrettyJson(definition));
        payload.put("yaml", DefinitionCodec.toYaml(definition));
        return payload;
    }

    private static ResponseDto invalid(List<DefinitionProblem> problems, PipelineDefinition read) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("valid", false);
        payload.put("problems", problems);
        if (read != null) {
            payload.put("definition", read);
        }
        return new ResponseDto(ERROR, String.format("The definition has %d problem(s): %s", problems.size(),
            problems.stream().limit(3).map(DefinitionProblem::toString).collect(Collectors.joining("; "))
                + (problems.size() > 3 ? "; ..." : "")), payload);
    }
}
