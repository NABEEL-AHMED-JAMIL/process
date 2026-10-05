package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.dto.MessageQSearchDto;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.service.impl.QueryService;
import process.security.TenantContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale review 2026-10-05, P0 #1-#3 and P1 #20: the reads that walked a whole history, planned against a job_queue
 * sized like a busy year -- 200,000 runs of one minute job beside 200,000 across 400 others, each with its
 * 'Job started' line.
 *
 * Plans are asserted, not times: a small table is fast whatever the plan. The times of the report's old and new
 * shape are printed beside them, for the record.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class ScaleReadsPostgresTest {

    private static final long MINUTE_JOB = 1L;
    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("scale_reads");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2901, 'Active', 'CHS', 'CareBridge')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "SELECT g, '2025-09-01', 'Auto', 'job ' || g, 'Active', 1, 2901 FROM generate_series(1, 400) g");
        // Finished statuses only: a job may hold one in-flight run (V83).
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, end_time, tenant_id) "
            + "SELECT g, timestamp '2025-09-22' + (g * interval '79 seconds'), "
            + "CASE WHEN g % 2 = 0 THEN 1 ELSE 2 + g % 399 END, "
            + "(ARRAY['Completed','Failed','Interrupt','Skip'])[1 + g % 4], 'Active', "
            + "timestamp '2025-09-22' + (g * interval '79 seconds'), timestamp '2025-09-22' + (g * interval '79 seconds') + interval '40 seconds', "
            + "2901 FROM generate_series(1, 400000) g");
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status, tenant_id) "
            + "SELECT g, timestamp '2025-09-22' + (g * interval '79 seconds') + interval '1 second', g, 'Job started', 'Active', 2901 "
            + "FROM generate_series(1, 400000) g");
        sql.execute("ANALYZE job_queue");
        sql.execute("ANALYZE job_audit_logs");
        sql.execute("ANALYZE source_job");
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

    private static String plan(String query) {
        return String.join("\n", db.sql().queryForList("EXPLAIN " + query, String.class));
    }

    private static double executionMs(String query) {
        List<String> lines = db.sql().queryForList("EXPLAIN ANALYZE " + query, String.class);
        String last = lines.stream().filter(line -> line.startsWith("Execution Time")).findFirst().orElse("Execution Time: -1 ms");
        return Double.parseDouble(last.replaceAll("[^0-9.]", ""));
    }

    private static String nativeQueryOf(Class<?> repository, String method) {
        Method found = Arrays.stream(repository.getDeclaredMethods()).filter(m -> m.getName().equals(method)).findFirst()
            .orElseThrow(() -> new AssertionError(method + " is gone"));
        return found.getAnnotation(Query.class).value();
    }

    /** P0 #1: a job's newest runs are an index read of the window, not its 200,000-run history sorted. */
    @Test
    void aJobsNewestRunsReadOnlyTheWindow() {
        String window = nativeQueryOf(JobQueueRepository.class, "findRecentByJobId")
            .replace(":jobId", String.valueOf(MINUTE_JOB)).replace(":beforeId", String.valueOf(Long.MAX_VALUE)).replace(":limit", "51");
        String shown = plan(window);
        System.out.println("-- findRecentByJobId\n" + shown);
        // Either index walk is a read of the window; what must not happen is the history fetched and sorted.
        assertThat(shown).contains("Limit").contains("Index").doesNotContain("Sort").doesNotContain("Seq Scan");
        assertThat(db.sql().queryForList(window).size()).isEqualTo(51);

        // A job among many: its 500 runs are scattered through the year, and (job_id, job_queue_id) is the only
        // walk that reaches its newest 51 without sorting all of them (V200).
        String sparse = nativeQueryOf(JobQueueRepository.class, "findRecentByJobId")
            .replace(":jobId", "7").replace(":beforeId", String.valueOf(Long.MAX_VALUE)).replace(":limit", "51");
        String sparsePlan = plan(sparse);
        System.out.println("-- findRecentByJobId, one job of 400\n" + sparsePlan);
        assertThat(sparsePlan).contains("idx_job_queue_job_id_run").doesNotContain("Sort");

        String newest = nativeQueryOf(JobQueueRepository.class, "findNewestRunIdAfter")
            .replace(":jobId", String.valueOf(MINUTE_JOB)).replace(":afterId", "1000");
        assertThat(plan(newest)).contains("Index").doesNotContain("Seq Scan").doesNotContain("Aggregate");

        double whole = executionMs("select job_queue.* from job_queue where job_id = " + MINUTE_JOB);
        double windowed = executionMs(window);
        System.out.printf("RUN HISTORY  whole history %.1f ms (200,000 rows)  newest 51 %.2f ms%n", whole, windowed);
    }

    /** P0 #2: the report's runs are capped in SQL, by the day index, with the start marker looked up per run. */
    @Test
    void theReportReadsItsRangeAndItsCapOnly() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        String report = new QueryService().runReportRows("2026-06-08", "2026-06-14");
        String shown = plan(report);
        System.out.println("-- runReportRows\n" + shown);
        assertThat(report).endsWith("limit " + (QueryService.REPORT_ROW_CAP + 1));
        assertThat(shown).contains("idx_job_queue_run_day").contains("idx_job_audit_logs_job_queue_id")
            .doesNotContain("Seq Scan on job_audit_logs");

        // The shape it replaced, for the record: every 'Job started' line grouped before the join.
        String before = report.substring(0, report.lastIndexOf(" limit "))
            .replace("left join lateral (select min(a.date_created) as exec_start from job_audit_logs a "
                + "where a.job_queue_id = q.job_queue_id and a.log_detail = 'Job started') x on true ",
                "left join (select job_queue_id, min(date_created) as exec_start from job_audit_logs "
                + "where log_detail = 'Job started' group by job_queue_id) x on x.job_queue_id = q.job_queue_id ");
        assertThat(before).as("the old shape was rebuilt").isNotEqualTo(report).contains("group by job_queue_id");
        System.out.printf("REPORT  one week, old shape %.1f ms  new %.1f ms%n", executionMs(before), executionMs(report));
    }

    /** P0 #3: the Queue's rows stop at the window and its counts read the range, both on the day index. */
    @Test
    void theQueueReadsAWindowAndCountsTheRange() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        MessageQSearchDto week = new MessageQSearchDto();
        week.setFromDate("2026-06-08");
        week.setToDate("2026-06-14");
        QueryService queries = new QueryService();
        String rows = queries.fetchJobQLog(week, false);
        String counts = queries.fetchJobQLog(week, true);
        assertThat(rows).contains("limit " + (QueryService.QUEUE_PAGE_DEFAULT + 1) + " offset 0");
        assertThat(plan(rows)).contains("Limit");
        String countPlan = plan(counts);
        System.out.println("-- fetchJobQLog counts\n" + countPlan);
        assertThat(countPlan).contains("idx_job_queue_date_created_day");
        long counted = db.sql().queryForList(counts).stream().mapToLong(row -> ((Number) row.get("total_count")).longValue()).sum();
        assertThat(counted).as("a week of runs, not the year").isBetween(7_000L, 8_000L);

        week.setLimit(100_000);
        assertThat(queries.fetchJobQLog(week, false)).as("the ceiling holds")
            .contains("limit " + (QueryService.QUEUE_PAGE_MAX + 1) + " ");
    }

    /** P1 #20: which jobs ever ran, and their schedulers, read with one array parameter, not an IN list per id. */
    @Test
    void theJobListAsksAboutEveryJobWithOneParameter() {
        String ids = JobQueueRepository.idArray(LongStream.rangeClosed(1, 400).boxed().collect(Collectors.toList()));
        String exists = nativeQueryOf(JobQueueRepository.class, "findJobIdsWithRuns").replace(":jobIds", "'" + ids + "'");
        String shown = plan(exists);
        System.out.println("-- findJobIdsWithRuns\n" + shown);
        assertThat(shown).contains("idx_job_queue_job_id").doesNotContain("Seq Scan on job_queue");
        assertThat(db.sql().queryForList(exists)).hasSize(400);

        String schedulers = nativeQueryOf(SchedulerRepository.class, "findAllForJobIds").replace(":jobIds", "'" + ids + "'");
        assertThat(db.sql().queryForList(schedulers)).isEmpty();
        assertThat(JobQueueRepository.idArray(Arrays.asList(3L, null, 5L))).isEqualTo("{3,5}");
    }
}
