package process.pipeline;

import org.barco.platform.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.dto.SourceTaskDto;
import process.model.enums.JobStatus;
import process.model.service.impl.QueryService;
import process.pipeline.registry.TaskKind;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.EnumUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * UX review 2026-10-13 (Pipelines list, review P1): what a reader of the list needs instead of Topic, Registry task and
 * Storage -- per row, from the pipeline's latest definition, its engine steps (0 for a legacy pipeline), its Output steps
 * (the Task Registry's kind, not task names), the sensitivity it says (or none), whether its results wait for a review
 * (settings.review.required, or an approval step); and its newest run the caller may see. Two queries for the page,
 * whatever its size: the definitions (PipelineDefinitionStore.latestForTasks) and the newest runs
 * (QueryService.lastRunsOfTasks).
 */
@Component
public class PipelineSummaries {

    /** The approval step's task: a pipeline holding one waits for a person (ApprovalStepTask). */
    static final String APPROVAL_TASK = "approval";

    private final Logger logger = LoggerFactory.getLogger(PipelineSummaries.class);

    private final PipelineDefinitionStore definitions;
    private final StepTasks tasks;
    private final QueryService queries;

    public PipelineSummaries(PipelineDefinitionStore definitions, StepTasks tasks, QueryService queries) {
        this.definitions = definitions;
        this.tasks = tasks;
        this.queries = queries;
    }

    /** Sets stepCount, outputCount, sensitivity, reviewRequired and lastRun on every row of the page. */
    public void attach(List<SourceTaskDto> rows) {
        List<Long> ids = rows == null ? new ArrayList<>() : rows.stream().map(SourceTaskDto::getTaskDetailId).filter(Objects::nonNull)
            .distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return;
        }
        TenantScope scope = TenantContext.scope();
        Long tenantId = scope.isAllTenants() ? null : ((TenantScope.Scoped) scope).tenantId();
        Map<Long, PipelineDefinition> byTask = new HashMap<>();
        for (PipelineDefinitionStore.TaskDefinition task : this.definitions.latestForTasks(tenantId, ids)) {
            try {
                byTask.put(task.taskDetailId, task.stored.definition());
            } catch (RuntimeException unreadable) {
                this.logger.warn("Pipeline {}'s definition {} is not summarised: {}", task.taskDetailId, task.stored.id, unreadable.getMessage());
            }
        }
        Map<Long, Map<String, Object>> lastRuns = this.lastRuns(ids);
        for (SourceTaskDto row : rows) {
            this.summarise(row, byTask.get(row.getTaskDetailId()));
            row.setLastRun(lastRuns.get(row.getTaskDetailId()));
        }
    }

    /** One row's definition facts; a legacy pipeline (no definition, or the legacy wrap) has no steps and says nothing. */
    void summarise(SourceTaskDto row, PipelineDefinition definition) {
        if (definition == null || definition.isLegacy()) {
            row.setStepCount(0);
            row.setOutputCount(0);
            row.setSensitivity(null);
            row.setReviewRequired(false);
            return;
        }
        int outputs = 0;
        boolean approval = false;
        for (PipelineDefinition.Step step : definition.getSteps()) {
            Optional<StepTask> task = this.tasks.find(step.getTask());
            if (task.isPresent() && task.get().spec().kind() == TaskKind.OUTPUT) {
                outputs++;
            }
            approval |= APPROVAL_TASK.equals(step.getTask());
        }
        PipelineDefinition.Settings settings = definition.getSettings();
        boolean reviewSaid = settings != null && settings.getReview() != null && settings.getReview().getRequired() != null
            && settings.getReview().getRequired().stream().anyMatch(word -> word != null && !word.trim().isEmpty());
        row.setStepCount(definition.getSteps().size());
        row.setOutputCount(outputs);
        row.setSensitivity(settings == null ? null : settings.getSensitivity());
        row.setReviewRequired(reviewSaid || approval);
    }

    /** taskDetailId -> {jobQueueId, jobId, jobStatus, at}: the newest run the caller may see of any of its schedules. */
    private Map<Long, Map<String, Object>> lastRuns(List<Long> ids) {
        Map<Long, Map<String, Object>> found = new LinkedHashMap<>();
        List<Object[]> rows = this.queries.executeQuery(this.queries.lastRunsOfTasks(ids));
        for (Object[] row : rows == null ? new ArrayList<Object[]>() : rows) {
            if (row[0] == null || row[1] == null) {
                continue;
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("jobQueueId", ((Number) row[1]).longValue());
            run.put("jobId", row[2] == null ? null : ((Number) row[2]).longValue());
            JobStatus status = row[3] == null ? null : EnumUtils.parseEnum(JobStatus.class, String.valueOf(row[3]));
            run.put("jobStatus", status == null ? (row[3] == null ? null : String.valueOf(row[3])) : status.name());
            run.put("at", row[4] == null ? null : BusinessTime.wallClockOf(row[4]).withNano(0));
            found.put(((Number) row[0]).longValue(), run);
        }
        return found;
    }
}
