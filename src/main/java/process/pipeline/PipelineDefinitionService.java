package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.repository.PipelineRepository;
import process.security.TenantContext;
import process.security.TenantOwnership;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A pipeline's definition as ordered steps, for the console's step builder (MIG-230, MIG-249): read it (the stored
 * latest, or the legacy wrap every pipeline without one has), check a draft, save it as the next version, and list
 * the tasks a step may run. A pipeline is the caller's workspace's or not found -- the same rule, and the same words,
 * as the pipeline's own form (PipelineServiceImpl.fieldsFor).
 */
@Service
public class PipelineDefinitionService {

    static final String PIPELINE_NOT_FOUND = "That pipeline no longer exists.";

    private final Logger logger = LoggerFactory.getLogger(PipelineDefinitionService.class);

    private final PipelineRepository pipelines;
    private final PipelineDefinitionStore store;
    private final DefinitionValidator validator;
    private final StepTasks tasks;

    public PipelineDefinitionService(PipelineRepository pipelines, PipelineDefinitionStore store, DefinitionValidator validator,
        StepTasks tasks) {
        this.pipelines = pipelines;
        this.store = store;
        this.validator = validator;
        this.tasks = tasks;
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
        payload.put("versions", this.store.versions(pipeline.get().getPipelineKey()).stream().map(stored -> {
            Map<String, Object> version = new LinkedHashMap<>();
            version.put("version", stored.version);
            version.put("pipelineDefinitionId", stored.id);
            version.put("createdBy", stored.createdBy);
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
        List<DefinitionProblem> problems = this.validator.problems(definition);
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
            definition = this.validator.require(DefinitionCodec.read(request.getText(), request.getFormat()));
        } catch (DefinitionException ex) {
            return invalid(ex.getProblems(), null);
        }
        String json = DefinitionCodec.toJson(definition);
        Optional<PipelineDefinitionStore.Stored> latest = this.store.latest(pipeline.get().getPipelineKey());
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

    /** The tasks a step may run, for "Add step". */
    public ResponseDto tasks() {
        List<Map<String, Object>> list = this.tasks.all().stream().map(task -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", task.code());
            entry.put("description", task.description());
            entry.put("runsInEngine", task.runsInEngine());
            return entry;
        }).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, String.format("%d step task(s).", list.size()), list);
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
