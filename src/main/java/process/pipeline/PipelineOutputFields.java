package process.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.security.JobOwnership;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MIG-424: what a caller of a pipeline needs to know to call it and use its answer -- a workflow's run_pipeline step in the
 * designer (pick the pipeline, map its parameters, choose result fields), a workflow being published (its parameter names
 * checked), a pipeline's Run pipeline step. For one job (schedule) of the caller's workspace:
 *
 * <ul>
 *   <li>its name, whether it is active and whether it runs Core's steps (a legacy pipeline answers no rows);</li>
 *   <li>its declared parameters ({@code param.*}, as Run with... offers them: name, type, label, required, default, choices);</li>
 *   <li>its steps in order, each with the columns its output had in the job's latest completed run (none before a first
 *   one), and {@code final}: the columns of the last step that kept an output -- what {@code from: final} reads.</li>
 * </ul>
 * Column names only, never a row. Another workspace's job, or one the caller may not see, is not there (empty).
 */
@Component
public class PipelineOutputFields {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransactionServiceImpl jobs;
    private final PipelineDefinitionStore definitions;
    private final PipelineParameters parameters;
    private final StepStore steps;
    private final JdbcTemplate sql;

    public PipelineOutputFields(TransactionServiceImpl jobs, PipelineDefinitionStore definitions, PipelineParameters parameters,
        StepStore steps, JdbcTemplate sql) {
        this.jobs = jobs;
        this.definitions = definitions;
        this.parameters = parameters;
        this.steps = steps;
        this.sql = sql;
    }

    /** The job of this workspace, when the caller may see it. */
    public Optional<SourceJob> job(long tenantId, long jobId) {
        return this.jobs.findByJobId(jobId).filter(job -> job.getTenantId() != null && job.getTenantId() == tenantId
            && job.getJobStatus() != Status.Delete && JobOwnership.isVisibleToCaller(job));
    }

    /** The job's fields as above, or empty when it is not this workspace's (or not the caller's to see). */
    public Optional<Map<String, Object>> of(long tenantId, long jobId) {
        Optional<SourceJob> found = this.job(tenantId, jobId);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        SourceJob job = found.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getJobId());
        out.put("name", job.getJobName());
        out.put("active", job.getJobStatus() == Status.Active);
        PipelineDefinition definition = null;
        String pipelineId = job.getTaskDetail() == null ? null : job.getTaskDetail().getPipelineId();
        if (pipelineId != null) {
            Optional<PipelineDefinitionStore.Stored> stored = this.definitions.latestFor(tenantId, pipelineId);
            try {
                definition = stored.isPresent() ? stored.get().definition() : null;
            } catch (IllegalStateException unreadable) {
                definition = null;
            }
        }
        boolean stepPipeline = definition != null && !definition.isLegacy();
        out.put("pipelineId", pipelineId);
        out.put("stepPipeline", stepPipeline);
        List<Map<String, Object>> declared = new ArrayList<>();
        for (PipelineDefinition.Parameter p : this.parameters.of(job)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", p.getName());
            one.put("type", p.getType());
            one.put("label", p.getLabel());
            one.put("required", p.required());
            one.put("default", p.getDefaultValue());
            one.put("choices", p.getChoices());
            declared.add(one);
        }
        out.put("parameters", declared);
        Map<String, List<String>> columns = stepPipeline ? this.latestColumns(tenantId, job.getJobId()) : Collections.emptyMap();
        List<Map<String, Object>> stepList = new ArrayList<>();
        List<String> last = Collections.emptyList();
        if (stepPipeline && definition.getSteps() != null) {
            for (PipelineDefinition.Step step : definition.getSteps()) {
                if (step == null || step.getKey() == null) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", step.getKey());
                one.put("name", step.getName());
                one.put("task", step.getTask());
                List<String> own = columns.get(step.getKey());
                one.put("columns", own == null ? Collections.emptyList() : own);
                if (own != null) {
                    last = own;
                }
                stepList.add(one);
            }
        }
        out.put("steps", stepList);
        out.put("final", last);
        out.put("fromRun", columns.isEmpty() ? null : this.latestCompleted(tenantId, job.getJobId()));
        return Optional.of(out);
    }

    /** The job's latest completed run (its own or one beside it), or null. */
    Long latestCompleted(long tenantId, long jobId) {
        List<Long> ids = this.sql.queryForList("SELECT max(job_queue_id) FROM job_queue WHERE job_id = ? AND tenant_id = ? "
            + "AND job_status = 'Completed'", Long.class, jobId, tenantId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Each step's output columns in the job's latest completed run, by step key (its latest attempt's). */
    Map<String, List<String>> latestColumns(long tenantId, long jobId) {
        Long run = this.latestCompleted(tenantId, jobId);
        if (run == null) {
            return Collections.emptyMap();
        }
        Map<Long, StepStore.StepRow> byExecution = new HashMap<>();
        int attempt = 0;
        for (StepStore.StepRow row : this.steps.stepsOfRun(run)) {
            byExecution.put(row.stepExecutionId, row);
            attempt = Math.max(attempt, row.attempt);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (StepStore.DatasetRow dataset : this.steps.datasetsOfRun(run)) {
            StepStore.StepRow step = byExecution.get(dataset.stepExecutionId);
            if (step == null || step.attempt != attempt || !"output".equals(dataset.name) || dataset.columns == null) {
                continue;
            }
            try {
                JsonNode list = JSON.readTree(dataset.columns);
                List<String> names = new ArrayList<>();
                list.forEach(c -> names.add(c.asText()));
                out.put(step.stepKey, names);
            } catch (Exception unreadable) {
                // a dataset whose columns cannot be read names none
            }
        }
        return out;
    }
}
