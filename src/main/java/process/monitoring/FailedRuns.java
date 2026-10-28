package process.monitoring;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.security.JobOwnership;
import process.util.ListSort;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * MIG-355: Runs › Failed -- every failed or timed-out run of the window (Failed, and Interrupt: closed by the stall sweep),
 * newest first, with the step that failed and its error, and three answers over the same rows:
 *
 * <ul>
 *   <li><b>facets</b>: by workspace (a platform administrator's view only), pipeline, schedule, the failing step's task,
 *   error kind (ErrorSignatures.kind) and day; each facet counts the rows every OTHER filter leaves, so picking a value never
 *   empties its own list;</li>
 *   <li><b>groups</b>: by error signature (the failing task and its error's pattern, ErrorSignatures.pattern) with counts,
 *   first and last seen, the pipelines it hit and the newest run -- the "log patterns" view;</li>
 *   <li><b>items</b>: the rows the filters leave, a page at a time.</li>
 * </ul>
 *
 * One statement: the window's failed runs over idx_job_queue_failed_ended, each with its first failed step of its latest
 * attempt (a LATERAL on ux_step_execution_run_step), its schedule and pipeline -- at most {@value #MAX_SCAN} rows, newest
 * first (the answer says when the window held more). Row security decides whose: a workspace's people their own, a platform
 * administrator every workspace's; and, as for every run read, a tenant user only the runs of jobs that name them
 * (JobOwnership: they made it or it is assigned to them).
 */
@Component
public class FailedRuns {

    public static final int MAX_SCAN = 2000;
    public static final int PAGE = 50;
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    public static final Duration LONGEST = Duration.ofDays(90);

    private final JdbcTemplate jdbc;
    private final IdentityPort identity;

    public FailedRuns(JdbcTemplate jdbc, IdentityPort identity) {
        this.jdbc = jdbc;
        this.identity = identity;
    }

    /** What the page asks for. Every list empty means "any". */
    public static final class Query {
        public String from;
        public String to;
        public List<Long> tenantIds = new ArrayList<>();
        public List<Long> taskDetailIds = new ArrayList<>();
        public List<Long> jobIds = new ArrayList<>();
        public List<String> tasks = new ArrayList<>();
        public List<String> kinds = new ArrayList<>();
        public List<String> days = new ArrayList<>();
        public String signature;
        public Integer page;
        /** Owner 2026-10-27 ("add a sort on each table"): a column of {@link #sorts} and asc/desc; none is newest first. */
        public String sort;
        public String dir;
    }

    /**
     * Owner 2026-10-27: the Runs list's sortable columns -- Run, Pipeline, Kind (its label, as shown), Ended, and for a platform
     * administrator Workspace (its name). The rows are the scanned window's (at most {@value #MAX_SCAN}, the facets' and
     * patterns' too), so the sort is over every row the filters leave, before the page is cut; ties go by the run, newest
     * first when descending. In Java: the window is already in memory, and no index would serve a sort of it.
     */
    static List<String> sorts(boolean platform) {
        List<String> out = new ArrayList<>(Arrays.asList("run", "pipeline", "kind", "ended"));
        if (platform) {
            out.add("workspace");
        }
        return out;
    }

    static Comparator<Row> order(ListSort.Choice sort, Map<Long, String> workspaceNames) {
        Comparator<Row> tie = Comparator.comparingLong((Row r) -> r.runId);
        switch (sort.column) {
            case "run":
                return ListSort.comparator((Row r) -> Long.valueOf(r.runId), sort.dir, tie);
            case "pipeline":
                return ListSort.comparator((Row r) -> lower(r.taskName), sort.dir, tie);
            case "kind":
                return ListSort.comparator((Row r) -> r.kind == null ? null : lower(ErrorSignatures.label(r.kind)), sort.dir, tie);
            case "workspace":
                return ListSort.comparator((Row r) -> lower(workspaceNames.get(r.tenantId)), sort.dir, tie);
            default:
                return ListSort.comparator((Row r) -> r.ended, sort.dir, tie);
        }
    }

    private static String lower(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT);
    }

    /** One failed run. */
    static final class Row {
        long runId;
        long tenantId;
        long jobId;
        String jobName;
        Long taskDetailId;
        String taskName;
        String status;
        String endReason;
        int attempt;
        Instant started;
        Instant ended;
        String runMessage;
        Integer stepIndex;
        String stepKey;
        String task;
        String stepError;
        Boolean timedOut;
        String kind;
        String pattern;
        String signature;
        String day;
        /** UX review 2026-10-13: the backfill this run is part of (its Retry failed retries it), else null. */
        Long backfillId;

        String error() {
            return this.stepError != null && !this.stepError.trim().isEmpty() ? this.stepError : this.runMessage;
        }
    }

    public Map<String, Object> search(Query q, boolean platform) {
        ListSort.Choice sort;
        try {
            sort = ListSort.parse(q.sort, q.dir, sorts(platform));
        } catch (ListSort.Refused unknown) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, unknown.getMessage());
        }
        Instant to;
        Instant from;
        try {
            to = blank(q.to) ? Instant.now() : Instant.parse(q.to.trim());
            from = blank(q.from) ? to.minus(DEFAULT_WINDOW) : Instant.parse(q.from.trim());
        } catch (DateTimeParseException unreadable) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "from and to are instants with an offset, e.g. 2026-10-01T00:00:00Z.");
        }
        if (!from.isBefore(to)) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "from must be before to.");
        }
        if (Duration.between(from, to).compareTo(LONGEST) > 0) {
            throw new AlertRules.Refused(HttpStatus.BAD_REQUEST, "Ask for at most " + LONGEST.toDays() + " days at a time.");
        }
        List<Row> rows = this.jdbc.query("SELECT q.job_queue_id, q.tenant_id, q.job_id, j.job_name, j.task_detail_id, t.task_name, q.job_status, "
            + "q.end_reason, q.attempt, q.backfill_id, q.start_time, COALESCE(q.end_time, q.skip_time) AS ended_at, q.job_status_message, s.step_index, "
            + "s.step_key, s.task_code, s.error ->> 'message' AS step_error, (s.error ->> 'timedOut')::boolean AS timed_out, "
            + "to_char(COALESCE(q.end_time, q.skip_time) AT TIME ZONE 'America/Chicago', 'YYYY-MM-DD') AS day "
            + "FROM job_queue q JOIN source_job j ON j.job_id = q.job_id LEFT JOIN source_task t ON t.task_detail_id = j.task_detail_id "
            + "LEFT JOIN LATERAL (SELECT e.step_index, e.step_key, e.task_code, e.error FROM step_execution e WHERE e.job_queue_id = q.job_queue_id "
            + "AND e.attempt = q.attempt AND e.status IN ('Failed', 'Interrupt') ORDER BY e.step_index LIMIT 1) s ON true "
            + "WHERE q.job_status IN ('Failed', 'Interrupt') AND COALESCE(q.end_time, q.skip_time) >= ? AND COALESCE(q.end_time, q.skip_time) < ? "
            + "AND j.job_status <> 'Delete'" + JobOwnership.sqlPredicate("j")
            + " ORDER BY COALESCE(q.end_time, q.skip_time) DESC, q.job_queue_id DESC LIMIT " + (MAX_SCAN + 1), (rs, n) -> {
                Row r = new Row();
                r.runId = rs.getLong("job_queue_id");
                r.tenantId = rs.getLong("tenant_id");
                r.jobId = rs.getLong("job_id");
                r.jobName = rs.getString("job_name");
                long task = rs.getLong("task_detail_id");
                r.taskDetailId = rs.wasNull() ? null : task;
                r.taskName = rs.getString("task_name");
                r.status = rs.getString("job_status");
                r.endReason = rs.getString("end_reason");
                r.attempt = rs.getInt("attempt");
                long backfill = rs.getLong("backfill_id");
                r.backfillId = rs.wasNull() ? null : backfill;
                Timestamp started = rs.getTimestamp("start_time");
                r.started = started == null ? null : started.toInstant();
                Timestamp ended = rs.getTimestamp("ended_at");
                r.ended = ended == null ? null : ended.toInstant();
                r.runMessage = rs.getString("job_status_message");
                int index = rs.getInt("step_index");
                r.stepIndex = rs.wasNull() ? null : index;
                r.stepKey = rs.getString("step_key");
                r.task = rs.getString("task_code");
                r.stepError = rs.getString("step_error");
                boolean timed = rs.getBoolean("timed_out");
                r.timedOut = rs.wasNull() ? null : timed;
                r.day = rs.getString("day");
                r.kind = ErrorSignatures.kind(r.status, r.endReason, r.timedOut, r.error());
                r.pattern = ErrorSignatures.pattern(r.error());
                r.signature = ErrorSignatures.id(r.task, r.pattern);
                return r;
            }, Timestamp.from(from), Timestamp.from(to));
        boolean truncated = rows.size() > MAX_SCAN;
        if (truncated) {
            rows = rows.subList(0, MAX_SCAN);
        }

        Predicate<Row> byTenant = r -> q.tenantIds.isEmpty() || q.tenantIds.contains(r.tenantId);
        Predicate<Row> byPipeline = r -> q.taskDetailIds.isEmpty() || (r.taskDetailId != null && q.taskDetailIds.contains(r.taskDetailId));
        Predicate<Row> bySchedule = r -> q.jobIds.isEmpty() || q.jobIds.contains(r.jobId);
        Predicate<Row> byTask = r -> q.tasks.isEmpty() || q.tasks.contains(r.task == null ? "" : r.task);
        Predicate<Row> byKind = r -> q.kinds.isEmpty() || q.kinds.contains(r.kind);
        Predicate<Row> byDay = r -> q.days.isEmpty() || q.days.contains(r.day);
        Predicate<Row> bySignature = r -> blank(q.signature) || q.signature.trim().equals(r.signature);

        Map<Long, String> workspaceNames = platform ? this.workspaceNames(rows) : new HashMap<>();
        Map<String, Object> facets = new LinkedHashMap<>();
        if (platform) {
            facets.put("workspaces", facet(rows, all(byPipeline, bySchedule, byTask, byKind, byDay, bySignature), r -> String.valueOf(r.tenantId),
                r -> workspaceNames.getOrDefault(r.tenantId, "Workspace " + r.tenantId)));
        }
        facets.put("pipelines", facet(rows, all(byTenant, bySchedule, byTask, byKind, byDay, bySignature),
            r -> r.taskDetailId == null ? "" : String.valueOf(r.taskDetailId), r -> r.taskName == null ? "(no pipeline)" : r.taskName));
        facets.put("schedules", facet(rows, all(byTenant, byPipeline, byTask, byKind, byDay, bySignature), r -> String.valueOf(r.jobId),
            r -> r.jobName));
        facets.put("steps", facet(rows, all(byTenant, byPipeline, bySchedule, byKind, byDay, bySignature), r -> r.task == null ? "" : r.task,
            r -> r.task == null ? "(no step: the run itself)" : r.task));
        facets.put("kinds", facet(rows, all(byTenant, byPipeline, bySchedule, byTask, byDay, bySignature), r -> r.kind,
            r -> ErrorSignatures.label(r.kind)));
        facets.put("days", facet(rows, all(byTenant, byPipeline, bySchedule, byTask, byKind, bySignature), r -> r.day, r -> r.day));

        Predicate<Row> everything = all(byTenant, byPipeline, bySchedule, byTask, byKind, byDay, bySignature);
        List<Row> kept = new ArrayList<>();
        for (Row r : rows) {
            if (everything.test(r)) {
                kept.add(r);
            }
        }
        // The patterns: every filter but the signature's own, so the list of patterns stays to pick from.
        Predicate<Row> allButSignature = all(byTenant, byPipeline, bySchedule, byTask, byKind, byDay);
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        Map<String, Set<Long>> groupPipelines = new HashMap<>();
        for (Row r : rows) {
            if (!allButSignature.test(r)) {
                continue;
            }
            Map<String, Object> g = groups.computeIfAbsent(r.signature, s -> {
                Map<String, Object> made = new LinkedHashMap<>();
                made.put("signature", s);
                made.put("pattern", r.pattern);
                made.put("task", r.task);
                made.put("kind", r.kind);
                made.put("kindLabel", ErrorSignatures.label(r.kind));
                made.put("count", 0);
                made.put("firstSeen", r.ended == null ? null : r.ended.toString());
                made.put("lastSeen", r.ended == null ? null : r.ended.toString());
                made.put("sampleRunId", r.runId);
                made.put("sampleJobId", r.jobId);
                made.put("sampleError", AlertIncidents.cut(r.error(), 600));
                return made;
            });
            g.put("count", (Integer) g.get("count") + 1);
            // Rows come newest first: the last one seen is the first occurrence.
            g.put("firstSeen", r.ended == null ? g.get("firstSeen") : r.ended.toString());
            groupPipelines.computeIfAbsent(r.signature, s -> new HashSet<>()).add(r.jobId);
        }
        List<Map<String, Object>> groupList = new ArrayList<>(groups.values());
        groupList.forEach(g -> g.put("schedules", groupPipelines.get((String) g.get("signature")).size()));
        groupList.sort(Comparator.comparing((Map<String, Object> g) -> (Integer) g.get("count")).reversed());

        if (sort != null) {
            kept.sort(order(sort, workspaceNames));
        }
        int page = q.page == null || q.page < 0 ? 0 : q.page;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = page * PAGE; i < Math.min(kept.size(), (page + 1) * PAGE); i++) {
            items.add(item(kept.get(i), workspaceNames));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("total", kept.size());
        out.put("scanned", rows.size());
        out.put("truncated", truncated);
        out.put("page", page);
        out.put("pageSize", PAGE);
        if (sort != null) {
            out.put("sort", sort.column);
            out.put("dir", sort.dirWord());
        }
        out.put("platform", platform);
        out.put("facets", facets);
        out.put("groups", groupList.size() > 100 ? groupList.subList(0, 100) : groupList);
        out.put("items", items);
        return out;
    }

    private Map<Long, String> workspaceNames(List<Row> rows) {
        Set<Long> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.tenantId));
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        try {
            for (IdentityPort.Workspace w : this.identity.workspaces(ids)) {
                names.put(w.getTenantId(), w.getName());
            }
        } catch (IdentityPort.Unavailable unavailable) {
            // The ids stand in for the names.
        }
        return names;
    }

    private static Map<String, Object> item(Row r, Map<Long, String> workspaceNames) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runId", r.runId);
        item.put("tenantId", r.tenantId);
        item.put("workspace", workspaceNames.get(r.tenantId));
        item.put("jobId", r.jobId);
        item.put("schedule", r.jobName);
        item.put("taskDetailId", r.taskDetailId);
        item.put("pipeline", r.taskName);
        item.put("status", r.status);
        item.put("endReason", r.endReason);
        item.put("attempt", r.attempt);
        item.put("startedAt", r.started == null ? null : r.started.toString());
        item.put("endedAt", r.ended == null ? null : r.ended.toString());
        item.put("stepIndex", r.stepIndex);
        item.put("stepKey", r.stepKey);
        item.put("task", r.task);
        item.put("error", AlertIncidents.cut(r.error(), 2000));
        item.put("timedOut", r.timedOut);
        item.put("kind", r.kind);
        item.put("kindLabel", ErrorSignatures.label(r.kind));
        item.put("signature", r.signature);
        item.put("pattern", r.pattern);
        item.put("link", "/pipelines/schedules/" + r.jobId + "/runs/" + r.runId);
        // A backfill's run is retried by its backfill (Runs › Backfills, Retry failed), never here (RunRetries refuses it).
        item.put("backfillId", r.backfillId);
        item.put("retryable", "Failed".equals(r.status) && r.backfillId == null);
        return item;
    }

    /** {key, name, count} for the rows the filter leaves, most first. */
    private static List<Map<String, Object>> facet(List<Row> rows, Predicate<Row> filter, Function<Row, String> key, Function<Row, String> name) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!filter.test(r)) {
                continue;
            }
            Map<String, Object> one = out.computeIfAbsent(key.apply(r), k -> {
                Map<String, Object> made = new LinkedHashMap<>();
                made.put("key", k);
                made.put("name", name.apply(r));
                made.put("count", 0);
                return made;
            });
            one.put("count", (Integer) one.get("count") + 1);
        }
        List<Map<String, Object>> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparing((Map<String, Object> f) -> (Integer) f.get("count")).reversed());
        return list.size() > 200 ? list.subList(0, 200) : list;
    }

    @SafeVarargs
    private static Predicate<Row> all(Predicate<Row>... filters) {
        return r -> {
            for (Predicate<Row> f : filters) {
                if (!f.test(r)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Distinct, non-null, at most 200. */
    static <T> List<T> cap(Collection<T> values) {
        List<T> out = new ArrayList<>();
        if (values != null) {
            for (T v : values) {
                if (v != null && !out.contains(v) && out.size() < 200) {
                    out.add(v);
                }
            }
        }
        return out;
    }
}
