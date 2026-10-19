package process.pipeline;

import org.barco.platform.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.model.dto.ResponseDto;
import process.security.JobOwnership;
import process.security.TenantContext;
import process.util.BusinessTime;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Owner #17 (2026-10-19): how many pipelines use each Task Registry task, and how many runs those pipelines had this
 * month -- like Kafka's "n pipelines" -- and the lists behind each count.
 *
 * <ul>
 *   <li>A <b>step task</b> (code) is used by a pipeline -- a live source task, Active or Inactive -- whose step-engine
 *       definition's latest version has a step that runs it.</li>
 *   <li>A <b>Legacy row</b> (a registry pipeline, by pipelineKey) is used by the pipelines that name its pipeline id.</li>
 *   <li><b>Runs</b> are those pipelines' runs created since the first of this month (America/Chicago), not deleted.</li>
 * </ul>
 *
 * Counted on the server, at the moment of asking, in the caller's workspace: row security and an explicit tenant_id
 * keep a workspace administrator to their own; a platform administrator counts one workspace (the shared selector's
 * tenantId) or every workspace (none). Zero is an answer like any other.
 */
@Component
public class TaskUsage {

    static final int MAX_RUNS = 200;

    private final Logger logger = LoggerFactory.getLogger(TaskUsage.class);

    private final JdbcTemplate jdbc;
    private final PipelineDefinitionStore definitions;

    public TaskUsage(JdbcTemplate jdbc, PipelineDefinitionStore definitions) {
        this.jdbc = jdbc;
        this.definitions = definitions;
    }

    /** One pipeline that uses a task: its id, name, workspace, status, the steps that run the task, and its runs this month. */
    static final class Use {
        long taskDetailId;
        String taskName;
        String pipelineId;
        long tenantId;
        String status;
        /** The step keys that run the task asked about (pipelines()), or none (usage()). */
        final List<String> steps = new ArrayList<>();
        /** (task code, step key) of every step of the latest definition. */
        final List<String[]> runs = new ArrayList<>();
    }

    /** The counts for every task and every Legacy row of the caller's workspace (or the chosen one, or all). */
    public ResponseDto usage(Long requestedTenantId) {
        Scope scope = this.scope(requestedTenantId);
        if (scope.nothing) {
            return new ResponseDto(ERROR, "Tasks are used in a workspace; this caller has none.");
        }
        Map<String, Set<Long>> byCode = new TreeMap<>();
        for (Use use : this.stepUses(scope.tenantId)) {
            for (String[] step : use.runs) {
                byCode.computeIfAbsent(step[0], c -> new LinkedHashSet<>()).add(use.taskDetailId);
            }
        }
        Map<Long, Set<Long>> byPipeline = new LinkedHashMap<>();
        for (Object[] pair : this.legacyPairs(scope.tenantId)) {
            byPipeline.computeIfAbsent((Long) pair[0], k -> new LinkedHashSet<>()).add((Long) pair[1]);
        }
        Map<Long, Long> runs = this.runsThisMonth(scope.tenantId, null);
        List<Map<String, Object>> tasks = new ArrayList<>();
        byCode.forEach((code, ids) -> tasks.add(count("code", code, ids, runs)));
        List<Map<String, Object>> legacy = new ArrayList<>();
        byPipeline.forEach((key, ids) -> legacy.add(count("pipelineKey", key, ids, runs)));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("since", monthStart().toInstant().toString());
        answer.put("month", BusinessTime.today().withDayOfMonth(1).toString().substring(0, 7));
        answer.put("tenantId", scope.tenantId);
        answer.put("tasks", tasks);
        answer.put("legacy", legacy);
        return new ResponseDto(SUCCESS, String.format("%d task(s) and %d legacy pipeline(s) in use.", tasks.size(), legacy.size()), answer);
    }

    /** The pipelines that use one task (code) or one Legacy row (pipelineKey), each with its runs this month. */
    public ResponseDto pipelines(String code, Long pipelineKey, Long requestedTenantId) {
        Scope scope = this.scope(requestedTenantId);
        if (scope.nothing) {
            return new ResponseDto(ERROR, "Tasks are used in a workspace; this caller has none.");
        }
        if ((code == null || code.trim().isEmpty()) == (pipelineKey == null)) {
            return new ResponseDto(ERROR, "Name the task (code) or the Legacy row (pipelineKey), one of the two.");
        }
        List<Use> uses = this.uses(scope.tenantId, code, pipelineKey);
        Map<Long, Long> runs = this.runsThisMonth(scope.tenantId, ids(uses));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Use use : uses) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskDetailId", use.taskDetailId);
            row.put("taskName", use.taskName);
            row.put("pipelineId", use.pipelineId);
            row.put("tenantId", use.tenantId);
            row.put("status", use.status);
            row.put("steps", use.steps);
            row.put("runsThisMonth", runs.getOrDefault(use.taskDetailId, 0L));
            rows.add(row);
        }
        return new ResponseDto(SUCCESS, String.format("%d pipeline(s).", rows.size()), rows);
    }

    /** The runs this month of the pipelines that use one task or one Legacy row, newest first (at most 200). */
    public ResponseDto runs(String code, Long pipelineKey, Integer limit, Long requestedTenantId) {
        Scope scope = this.scope(requestedTenantId);
        if (scope.nothing) {
            return new ResponseDto(ERROR, "Tasks are used in a workspace; this caller has none.");
        }
        if ((code == null || code.trim().isEmpty()) == (pipelineKey == null)) {
            return new ResponseDto(ERROR, "Name the task (code) or the Legacy row (pipelineKey), one of the two.");
        }
        int max = limit == null || limit < 1 ? 50 : Math.min(limit, MAX_RUNS);
        List<Use> uses = this.uses(scope.tenantId, code, pipelineKey);
        Map<Long, String> names = new LinkedHashMap<>();
        uses.forEach(u -> names.put(u.taskDetailId, u.taskName));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!uses.isEmpty()) {
            List<Object> args = new ArrayList<>();
            args.add(Timestamp.from(monthStart().toInstant()));
            String in = placeholders(ids(uses), args);
            StringBuilder sql = new StringBuilder("SELECT jq.job_queue_id, jq.job_id, sj.job_name, sj.task_detail_id, jq.job_status, jq.tenant_id, "
                + "COALESCE(jq.start_time, jq.date_created) AS at, jq.end_time FROM job_queue jq JOIN source_job sj ON sj.job_id = jq.job_id "
                + "WHERE jq.date_created >= ? AND UPPER(jq.status) <> 'DELETE' AND sj.task_detail_id IN (" + in + ")");
            if (scope.tenantId != null) {
                sql.append(" AND jq.tenant_id = ?");
                args.add(scope.tenantId);
            }
            sql.append(JobOwnership.sqlPredicate("sj")).append(" ORDER BY jq.job_queue_id DESC LIMIT ?");
            args.add(max);
            this.jdbc.query(sql.toString(), rs -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jobQueueId", rs.getLong("job_queue_id"));
                row.put("jobId", rs.getLong("job_id"));
                row.put("jobName", rs.getString("job_name"));
                row.put("taskDetailId", rs.getLong("task_detail_id"));
                row.put("taskName", names.get(rs.getLong("task_detail_id")));
                row.put("jobStatus", rs.getString("job_status"));
                row.put("tenantId", rs.getLong("tenant_id"));
                OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
                OffsetDateTime end = rs.getObject("end_time", OffsetDateTime.class);
                row.put("at", at == null ? null : at.toInstant().toString());
                row.put("endTime", end == null ? null : end.toInstant().toString());
                rows.add(row);
            }, args.toArray());
        }
        long total = this.runsThisMonth(scope.tenantId, ids(uses)).values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("since", monthStart().toInstant().toString());
        answer.put("total", total);
        answer.put("pipelines", uses.size());
        answer.put("runs", rows);
        return new ResponseDto(SUCCESS, String.format("%d run(s) this month; the newest %d shown.", total, rows.size()), answer);
    }

    // ------------------------------------------------------------------------------------------------------- reading

    private List<Use> uses(Long tenantId, String code, Long pipelineKey) {
        if (pipelineKey != null) {
            return this.legacyUses(tenantId, pipelineKey);
        }
        String wanted = code.trim();
        List<Use> out = new ArrayList<>();
        for (Use use : this.stepUses(tenantId)) {
            for (String[] step : use.runs) {
                if (step[0].equals(wanted)) {
                    use.steps.add(step[1]);
                }
            }
            if (!use.steps.isEmpty()) {
                out.add(use);
            }
        }
        return out;
    }

    /** Every live pipeline with a step-engine definition, with the (task, step) pairs its latest version runs. */
    private List<Use> stepUses(Long tenantId) {
        List<Use> out = new ArrayList<>();
        for (PipelineDefinitionStore.TaskDefinition task : this.definitions.latestForTasks(tenantId, null)) {
            PipelineDefinition definition;
            try {
                definition = task.stored.definition();
            } catch (RuntimeException unreadable) {
                this.logger.warn("Pipeline {}'s definition {} is not counted: {}", task.taskDetailId, task.stored.id, unreadable.getMessage());
                continue;
            }
            if (definition == null || definition.isLegacy()) {
                continue;
            }
            Use use = new Use();
            use.taskDetailId = task.taskDetailId;
            use.taskName = task.taskName;
            use.pipelineId = task.pipelineId;
            use.tenantId = task.stored.tenantId;
            for (PipelineDefinition.Step step : definition.getSteps()) {
                if (step.getTask() != null && !step.getTask().trim().isEmpty()) {
                    use.runs.add(new String[] { step.getTask().trim(), step.getKey() });
                }
            }
            out.add(use);
        }
        this.statuses(out);
        return out;
    }

    /** (pipelineKey, taskDetailId) for every live pipeline naming a registry pipeline's id (its lowest live key, as the engine picks). */
    private List<Object[]> legacyPairs(Long tenantId) {
        List<Object> args = new ArrayList<>();
        String where = "st.task_status IN ('Active', 'Inactive')";
        if (tenantId != null) {
            where += " AND st.tenant_id = ?";
            args.add(tenantId);
        }
        return this.jdbc.query("SELECT k.pipeline_key, st.task_detail_id FROM source_task st JOIN LATERAL (SELECT p.pipeline_key FROM pipeline p "
            + "WHERE p.tenant_id = st.tenant_id AND p.pipeline_id = st.pipeline_id AND p.status <> 'Delete' ORDER BY p.pipeline_key LIMIT 1) k "
            + "ON true WHERE " + where, (rs, n) -> new Object[] { rs.getLong("pipeline_key"), rs.getLong("task_detail_id") }, args.toArray());
    }

    /** The live pipelines naming one registry pipeline's id (pipelineKey). */
    private List<Use> legacyUses(Long tenantId, Long pipelineKey) {
        if (pipelineKey == null) {
            return Collections.emptyList();
        }
        List<Object> args = new ArrayList<>();
        args.add(pipelineKey);
        String where = "st.task_status IN ('Active', 'Inactive')";
        if (tenantId != null) {
            where += " AND st.tenant_id = ?";
            args.add(tenantId);
        }
        return this.jdbc.query("SELECT st.task_detail_id, st.task_name, st.pipeline_id, st.tenant_id, st.task_status FROM source_task st "
            + "JOIN LATERAL (SELECT p.pipeline_key FROM pipeline p WHERE p.tenant_id = st.tenant_id AND p.pipeline_id = st.pipeline_id "
            + "AND p.status <> 'Delete' ORDER BY p.pipeline_key LIMIT 1) k ON k.pipeline_key = ? WHERE " + where
            + " ORDER BY lower(st.task_name), st.task_detail_id", (rs, n) -> {
                Use use = new Use();
                use.taskDetailId = rs.getLong("task_detail_id");
                use.taskName = rs.getString("task_name");
                use.pipelineId = rs.getString("pipeline_id");
                use.tenantId = rs.getLong("tenant_id");
                use.status = rs.getString("task_status");
                return use;
            }, args.toArray());
    }

    /** Each step pipeline's status (Active or Inactive), one query. */
    private void statuses(List<Use> uses) {
        if (uses.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>();
        String in = placeholders(ids(uses), args);
        Map<Long, String> status = new LinkedHashMap<>();
        this.jdbc.query("SELECT task_detail_id, task_status FROM source_task WHERE task_detail_id IN (" + in + ")",
            rs -> { status.put(rs.getLong("task_detail_id"), rs.getString("task_status")); }, args.toArray());
        uses.forEach(u -> u.status = status.get(u.taskDetailId));
    }

    /** taskDetailId -> runs created since the first of the month; of these pipelines, or of every one (null). */
    private Map<Long, Long> runsThisMonth(Long tenantId, Collection<Long> taskDetailIds) {
        Map<Long, Long> runs = new LinkedHashMap<>();
        if (taskDetailIds != null && taskDetailIds.isEmpty()) {
            return runs;
        }
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(monthStart().toInstant()));
        StringBuilder sql = new StringBuilder("SELECT sj.task_detail_id, count(*) AS runs FROM job_queue jq JOIN source_job sj ON sj.job_id = jq.job_id "
            + "WHERE jq.date_created >= ? AND UPPER(jq.status) <> 'DELETE'");
        if (taskDetailIds != null) {
            sql.append(" AND sj.task_detail_id IN (").append(placeholders(taskDetailIds, args)).append(")");
        }
        if (tenantId != null) {
            sql.append(" AND jq.tenant_id = ?");
            args.add(tenantId);
        }
        sql.append(JobOwnership.sqlPredicate("sj")).append(" GROUP BY sj.task_detail_id");
        this.jdbc.query(sql.toString(), rs -> { runs.put(rs.getLong("task_detail_id"), rs.getLong("runs")); }, args.toArray());
        return runs;
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private static Map<String, Object> count(String key, Object value, Set<Long> pipelines, Map<Long, Long> runs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        row.put("pipelines", pipelines.size());
        row.put("runs", pipelines.stream().mapToLong(id -> runs.getOrDefault(id, 0L)).sum());
        return row;
    }

    private static Set<Long> ids(List<Use> uses) {
        Set<Long> ids = new LinkedHashSet<>();
        uses.forEach(u -> ids.add(u.taskDetailId));
        return ids;
    }

    private static String placeholders(Collection<Long> ids, List<Object> args) {
        List<String> marks = new ArrayList<>();
        for (Long id : ids) {
            marks.add("?");
            args.add(id);
        }
        return String.join(", ", marks);
    }

    /** The first of this month, 00:00 America/Chicago, as the platform counts a month. */
    static OffsetDateTime monthStart() {
        LocalDate first = BusinessTime.today().withDayOfMonth(1);
        return first.atStartOfDay(BusinessTime.ZONE).toOffsetDateTime();
    }

    /** Which workspace counts: a platform administrator's choice (null: every one); anyone else's own. */
    private Scope scope(Long requested) {
        Scope scope = new Scope();
        if (TenantContext.isPlatformAdmin()) {
            scope.tenantId = requested != null && requested > 0 ? requested : null;
            return scope;
        }
        TenantScope mine = TenantContext.scope();
        if (mine.isAllTenants()) {
            scope.tenantId = requested;
            return scope;
        }
        long id = ((TenantScope.Scoped) mine).tenantId();
        scope.nothing = id == TenantScope.NO_TENANT_MATCHES;
        scope.tenantId = id;
        return scope;
    }

    private static final class Scope {
        Long tenantId;
        boolean nothing;
    }
}
