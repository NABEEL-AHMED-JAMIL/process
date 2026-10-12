package process.pipeline;

import org.springframework.stereotype.Component;
import process.model.repository.PipelineRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which live pipelines use a prompt or an API request, for the services that own those (console review 2026-10-07,
 * finding M12): the old pipeline's AI fields (pipeline_field.prompt_id) and every step-engine pipeline whose latest
 * definition names it in a step's config ({@link StepReferences}). ai-service asks before it deletes a prompt and for its
 * "Used by" column; integration-service asks for an API collection's "Used by" and before it deletes a request.
 *
 * Read at the moment of asking, never copied into the other service: a definition saved a second ago counts, and one
 * that stopped naming the request stops counting, with nothing to keep in step.
 */
@Component
public class PipelineUsage {

    private final PipelineRepository pipelines;
    private final PipelineDefinitionStore definitions;
    private final StepReferences references;

    public PipelineUsage(PipelineRepository pipelines, PipelineDefinitionStore definitions, StepReferences references) {
        this.pipelines = pipelines;
        this.definitions = definitions;
        this.references = references;
    }

    /** How many live pipelines, of any workspace, run this prompt: as an old AI field or as a step-engine step's prompt. */
    public long countUsingPrompt(long promptId) {
        Set<Long> using = new HashSet<>(this.pipelines.pipelineKeysUsingPrompt(promptId));
        Set<Long> ids = Collections.singleton(promptId);
        using.addAll(this.references.using(this.definitions.latestMentioning(null, ids), StepReferences.PROMPT, ids).keySet());
        return using.size();
    }

    /**
     * The workspace's live step-engine pipelines whose steps run one of these API requests: one row per (pipeline,
     * request), with the steps that name it and the collection version they pin (null: the request as it is now; the
     * lowest when the steps differ, the one that falls behind first).
     */
    public List<Map<String, Object>> apiRequestUsers(long tenantId, Collection<Long> requestIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : requestIds == null ? Collections.<Long>emptyList() : requestIds) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (ids.isEmpty()) {
            return rows;
        }
        List<PipelineDefinitionStore.Named> found = this.definitions.latestMentioning(tenantId, ids);
        Map<Long, PipelineDefinitionStore.Named> byKey = new LinkedHashMap<>();
        found.forEach(n -> byKey.put(n.stored.pipelineKey, n));
        this.references.using(found, StepReferences.API_REQUEST, ids).forEach((pipelineKey, refs) -> {
            PipelineDefinitionStore.Named pipeline = byKey.get(pipelineKey);
            Map<Long, Map<String, Object>> perRequest = new LinkedHashMap<>();
            for (StepReferences.Ref ref : refs) {
                Map<String, Object> row = perRequest.computeIfAbsent(ref.id, id -> {
                    Map<String, Object> made = new LinkedHashMap<>();
                    made.put("pipelineKey", pipelineKey);
                    made.put("pipelineId", pipeline.pipelineId);
                    made.put("pipelineName", pipeline.pipelineName);
                    made.put("definitionVersion", pipeline.stored.version);
                    made.put("requestId", id);
                    made.put("version", null);
                    made.put("unpinned", false);
                    made.put("steps", new ArrayList<String>());
                    made.put("dateCreated", pipeline.stored.dateCreated == null ? null : pipeline.stored.dateCreated.toString());
                    return made;
                });
                @SuppressWarnings("unchecked")
                List<String> steps = (List<String>) row.get("steps");
                steps.add(ref.stepKey());
                Long pinned = StepReferences.idOf(ref.config().get("version"));
                if (pinned == null) {
                    row.put("unpinned", true);
                } else if (row.get("version") == null || pinned < ((Number) row.get("version")).longValue()) {
                    row.put("version", pinned.intValue());
                }
            }
            rows.addAll(perRequest.values());
        });
        return rows;
    }
}
