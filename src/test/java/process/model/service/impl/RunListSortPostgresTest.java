package process.model.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.dto.MessageQSearchDto;
import process.model.enums.JobStatus;
import process.pipeline.items.JdbcRunItemStore;
import process.pipeline.items.RunItemStore;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;
import process.util.ListSort;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner 2026-10-27 ("add a sort on each table"), against a real etl_job built by the changelog: a schedule's run history
 * (RunListSort.jobHistory), Runs › Latest (QueryService.fetchJobQLog) and a schedule's items across runs
 * (JdbcRunItemStore.acrossRuns) in every allowed column's order, both ways -- empty values last either way, ties by the id
 * in the sort's direction, windows by offset with no repeat and no gap -- and V236's per-job indexes reading a 100,000-run
 * job's sorted window without sorting its history.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class RunListSortPostgresTest {

    private static final long TENANT = 2911L;
    private static final long JOB = 1L;
    private static final long BIG = 2L;
    private static final LocalDateTime DAY = LocalDateTime.parse("2026-06-10T10:00:00");
    private static ScratchEtlJob db;

    /** A crafted run: id, minutes after DAY it was queued, started, skipped and ended (null: not), status. */
    private static final Object[][] RUNS = {
        {1L, 0, 1, null, 5, "Completed"},
        {2L, 10, null, 10, null, "Skip"},
        {3L, 20, 21, null, 22, "Failed"},
        // Queued and started with run 3: the run decides.
        {4L, 20, 21, null, 30, "Completed"},
        {5L, 40, 41, null, null, "Running"},
        // As long as run 3.
        {6L, 50, 51, null, 52, "Interrupt"},
        {7L, 60, null, null, null, "Missed"},
        // As long as run 1.
        {8L, 70, 71, null, 75, "Completed"},
    };

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("run_list_sort");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'RLS', 'Riverside (synthetic)')", TENANT);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "SELECT g, '2025-09-01', 'Auto', 'job ' || g, 'Active', 1, ? FROM generate_series(1, 402) g", TENANT);
        for (Object[] run : RUNS) {
            sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, skip_time, end_time, tenant_id) "
                + "VALUES (?, ?, ?, ?, 'Active', ?, ?, ?, ?)", run[0], at(run[1]), JOB, run[5], at(run[2]), at(run[3]), at(run[4]), TENANT);
        }
        // A busy job for the plans: 100,000 finished runs over a year, a tenth never started (skipped), a tenth not ended.
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, skip_time, end_time, tenant_id) "
            + "SELECT g, timestamp '2025-01-01' + g * interval '5 minutes', ?, 'Completed', 'Active', "
            + "CASE WHEN g % 10 = 0 THEN NULL ELSE timestamp '2025-01-01' + g * interval '5 minutes' + interval '10 seconds' END, "
            + "CASE WHEN g % 10 = 0 THEN timestamp '2025-01-01' + g * interval '5 minutes' END, "
            + "CASE WHEN g % 10 IN (0, 5) THEN NULL ELSE timestamp '2025-01-01' + g * interval '5 minutes' + (g % 97) * interval '1 second' END, ? "
            + "FROM generate_series(1000, 100999) g", BIG, TENANT);
        // And as many across 400 other jobs, as in ScaleReadsPostgresTest: the busy job is half the table.
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, end_time, tenant_id) "
            + "SELECT g, timestamp '2025-01-01' + (g - 200000) * interval '5 minutes', 3 + g % 400, 'Completed', 'Active', "
            + "timestamp '2025-01-01' + (g - 200000) * interval '5 minutes', timestamp '2025-01-01' + (g - 200000) * interval '5 minutes' "
            + "+ interval '30 seconds', ? FROM generate_series(200000, 299999) g", TENANT);
        sql.execute("ANALYZE job_queue");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Timestamp at(Object minutes) {
        return minutes == null ? null : Timestamp.valueOf(DAY.plusMinutes(((Number) minutes).longValue()));
    }

    // ---------------------------------------------------------------------------------------- the expected order, in Java

    private static LocalDateTime time(Object minutes) {
        return minutes == null ? null : DAY.plusMinutes(((Number) minutes).longValue());
    }

    private static final Map<String, Function<Object[], Comparable<?>>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("run", r -> (Long) r[0]);
        KEYS.put("queued", r -> time(r[1]));
        KEYS.put("started", r -> r[2] != null ? time(r[2]) : time(r[3]));
        KEYS.put("ended", r -> time(r[4]));
        KEYS.put("duration", r -> r[2] == null || r[4] == null ? null : Duration.between(time(r[2]), time(r[4])));
        KEYS.put("status", r -> RunListSort.STATUS_ORDER.indexOf(JobStatus.valueOf((String) r[5])));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Long> expected(String column, ListSort.Dir dir) {
        Function<Object[], Comparable> key = (Function) KEYS.get(column);
        int sign = dir == ListSort.Dir.ASC ? 1 : -1;
        List<Object[]> rows = new ArrayList<>();
        Collections.addAll(rows, RUNS);
        rows.sort((a, b) -> {
            Comparable x = key.apply(a);
            Comparable y = key.apply(b);
            if (x == null || y == null) {
                if (x != y) {
                    return x == null ? 1 : -1;
                }
            } else if (x.compareTo(y) != 0) {
                return x.compareTo(y) * sign;
            }
            return Long.compare((Long) a[0], (Long) b[0]) * sign;
        });
        return rows.stream().map(r -> (Long) r[0]).collect(Collectors.toList());
    }

    private static List<Long> history(ListSort.Choice choice, int limit, int offset) {
        String sql = RunListSort.jobHistory(choice).replace(":jobId", String.valueOf(JOB)).replace(":limit", String.valueOf(limit))
            .replace(":offset", String.valueOf(offset));
        return db.sql().queryForList(sql).stream().map(row -> ((Number) row.get("job_queue_id")).longValue()).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------------------------ tests

    @Test
    void aSchedulesHistorySortsByEveryColumnBothWaysAndPagesByOffset() {
        assertThat(RunListSort.COLUMNS.keySet()).containsExactly("run", "queued", "started", "ended", "duration", "status");
        for (String column : RunListSort.COLUMNS.keySet()) {
            for (ListSort.Dir dir : ListSort.Dir.values()) {
                ListSort.Choice choice = new ListSort.Choice(column, dir);
                List<Long> want = expected(column, dir);
                assertThat(history(choice, 100, 0)).as("%s %s", column, dir).containsExactlyElementsOf(want);
                List<Long> paged = new ArrayList<>();
                for (int offset = 0; offset < RUNS.length; offset += 3) {
                    paged.addAll(history(choice, 3, offset));
                }
                assertThat(paged).as("%s %s in windows of 3", column, dir).doesNotHaveDuplicates().containsExactlyElementsOf(want);
            }
        }
        // Spot checks of the rules, written out: a run never started or never ended is last both ways.
        assertThat(expected("ended", ListSort.Dir.ASC)).endsWith(2L, 5L, 7L);
        assertThat(expected("ended", ListSort.Dir.DESC)).endsWith(7L, 5L, 2L);
        assertThat(expected("started", ListSort.Dir.ASC)).startsWith(1L, 2L, 3L, 4L).endsWith(7L);
        assertThat(expected("status", ListSort.Dir.ASC)).startsWith(3L, 6L, 7L, 5L, 2L);
    }

    @Test
    void runsLatestSortsTheWindowsRunsByEveryColumnBothWays() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@riverside.test");
        QueryService queries = new QueryService();
        for (String column : RunListSort.COLUMNS.keySet()) {
            for (ListSort.Dir dir : ListSort.Dir.values()) {
                MessageQSearchDto search = new MessageQSearchDto("2026-06-09", "2026-06-11", Collections.singleton(JOB), null, null);
                search.setSort(column);
                search.setDir(dir.name().toLowerCase());
                List<Long> got = new ArrayList<>();
                for (int page = 0; page < 3; page++) {
                    search.setLimit(3);
                    search.setPage(page);
                    List<Map<String, Object>> rows = db.sql().queryForList(queries.fetchJobQLog(search, false));
                    // One row past the window says there is more; the next page starts after the window.
                    rows.stream().limit(3).forEach(row -> got.add(((Number) row.get("job_queue_id")).longValue()));
                }
                assertThat(got).as("%s %s", column, dir).doesNotHaveDuplicates().containsExactlyElementsOf(expected(column, dir));
            }
        }
        MessageQSearchDto plain = new MessageQSearchDto("2026-06-09", "2026-06-11", Collections.singleton(JOB), null, null);
        assertThat(queries.fetchJobQLog(plain, false)).as("no sort: newest first, exactly as before").contains("order by job_queue_id desc limit");
    }

    @Test
    void aSchedulesItemsAcrossRunsSortByEveryColumnBothWays() {
        JdbcTemplate sql = db.sql();
        String[] labels = {"delta.pdf", "Alpha.pdf", "charlie.pdf", "bravo.pdf", "echo.pdf", "alpha.pdf", "foxtrot.pdf", "golf.pdf"};
        String[] statuses = {"done", "failed", "done", "done", "running", "done", "failed", "done"};
        for (int i = 0; i < RUNS.length; i++) {
            sql.update("INSERT INTO run_item (job_queue_id, tenant_id, attempt, item_key, item_ref, label, position, status, reason, child_count, "
                + "child_failed, flag) VALUES (?, ?, 1, ?, '1', ?, 0, ?, ?, ?, ?, ?)", RUNS[i][0], TENANT, "inbox/" + labels[i], labels[i],
                statuses[i], "failed".equals(statuses[i]) ? "The file could not be read." : null, i == 2 ? 3 : 0, i == 2 ? 1 : 0,
                i == 3 ? "check" : null);
        }
        List<Map<String, Object>> made = sql.queryForList("SELECT i.run_item_id, i.job_queue_id, i.label, i.status, i.child_failed, i.flag, "
            + "COALESCE(q.start_time, q.date_created) AS run_started FROM run_item i JOIN job_queue q ON q.job_queue_id = i.job_queue_id "
            + "WHERE q.job_id = ?", JOB);
        Map<String, Function<Map<String, Object>, Comparable<?>>> keys = new LinkedHashMap<>();
        keys.put("run", r -> ((Number) r.get("job_queue_id")).longValue());
        keys.put("label", r -> ((String) r.get("label")).toLowerCase());
        keys.put("startedAt", r -> ((Timestamp) r.get("run_started")).toLocalDateTime());
        keys.put("status", r -> "failed".equals(r.get("status")) ? 0 : ((Number) r.get("child_failed")).intValue() > 0 ? 1
            : "check".equals(r.get("flag")) ? 2 : "running".equals(r.get("status")) ? 3 : 4);
        assertThat(keys.keySet()).containsExactlyElementsOf(RunItemStore.ACROSS_SORTS);
        JdbcRunItemStore store = new JdbcRunItemStore(sql);
        for (Map.Entry<String, Function<Map<String, Object>, Comparable<?>>> column : keys.entrySet()) {
            for (ListSort.Dir dir : ListSort.Dir.values()) {
                int sign = dir == ListSort.Dir.ASC ? 1 : -1;
                List<Map<String, Object>> rows = new ArrayList<>(made);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Comparator<Map<String, Object>> byKey = (a, b) -> ((Comparable) column.getValue().apply(a)).compareTo(column.getValue().apply(b));
                rows.sort((a, b) -> {
                    int by = byKey.compare(a, b) * sign;
                    return by != 0 ? by : Long.compare(((Number) a.get("run_item_id")).longValue(), ((Number) b.get("run_item_id")).longValue()) * sign;
                });
                RunItemStore.AcrossQuery query = new RunItemStore.AcrossQuery();
                query.sort = new ListSort.Choice(column.getKey(), dir);
                List<Long> got = store.acrossRuns(JOB, query, 100).stream().map(a -> a.item.runItemId).collect(Collectors.toList());
                assertThat(got).as("%s %s", column.getKey(), dir)
                    .containsExactlyElementsOf(rows.stream().map(r -> ((Number) r.get("run_item_id")).longValue()).collect(Collectors.toList()));
            }
        }
        sql.update("DELETE FROM run_item");
    }

    @Test
    void theSortedWindowsOfABusyJobAreAnIndexReadNotItsHistorySorted() {
        // Run is (job_id, job_queue_id) or the primary key, whichever the planner prefers for a job half the table holds.
        Map<String, String> served = new LinkedHashMap<>();
        served.put("run asc", "Index");
        served.put("run desc", "Index");
        served.put("queued asc", "idx_job_queue_job_queued");
        served.put("queued desc", "idx_job_queue_job_queued");
        served.put("started desc", "idx_job_queue_job_started");
        served.put("ended desc", "idx_job_queue_job_ended");
        served.put("duration desc", "idx_job_queue_job_duration");
        for (Map.Entry<String, String> one : served.entrySet()) {
            String[] parts = one.getKey().split(" ");
            String sql = RunListSort.jobHistory(new ListSort.Choice(parts[0], ListSort.Dir.valueOf(parts[1].toUpperCase())))
                .replace(":jobId", String.valueOf(BIG)).replace(":limit", "501").replace(":offset", "500");
            String shown = String.join("\n", db.sql().queryForList("EXPLAIN " + sql, String.class));
            System.out.println("-- " + one.getKey() + "\n" + shown);
            assertThat(shown).as(one.getKey()).contains(one.getValue()).doesNotContain("Sort").doesNotContain("Seq Scan");
            assertThat(db.sql().queryForList(sql)).hasSize(501);
        }
    }
}
