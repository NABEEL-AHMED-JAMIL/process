package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.dto.MessageQSearchDto;
import process.model.service.impl.QueryService;
import process.security.TenantContext;

import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-62 (DEF-131): the Dashboard's per-day reads, planned against a job_queue sized like production.
 *
 * Every one of them filters on date(date_created) -- or cast(date_created as date), which Postgres
 * treats as the same expression -- and a plain B-tree on date_created cannot serve a function-wrapped
 * column, so there was nothing to use: each request read the whole table. The plans are asserted, not
 * timed, because a sequential scan on a small table is fast and proves nothing; 400,000 runs over a year
 * is what makes the planner choose.
 *
 * And the other half: indexes declared on entities exist only where ddl-auto=update ran, and validate
 * does not check them. Each one must exist in a database built from the changelog alone.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class DashboardIndexPostgresTest {

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("dashboard_index");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2901, 'Active', 'CHS', 'CareBridge')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "SELECT g, '2025-09-01', 'Auto', 'job ' || g, 'Active', 1, 2901 FROM generate_series(1, 400) g");
        // 400,000 runs, about 1,100 a day for a year, spread over the hours.
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, end_time) "
            + "SELECT g, timestamp '2025-09-22' + (g * interval '79 seconds'), 1 + g % 400, "
            // Finished statuses only: since V83 (MIG-113) a job may hold one in-flight run, and 1,000 per job
            // would break that index; the date filter under test does not care which status a row has.
            + "(ARRAY['Completed','Failed','Interrupt','Skip'])[1 + g % 4], 'Active', "
            + "timestamp '2025-09-22' + (g * interval '79 seconds'), timestamp '2025-09-22' + (g * interval '79 seconds') + interval '40 seconds' "
            + "FROM generate_series(1, 400000) g");
        sql.execute("ANALYZE job_queue");
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

    /** Dashboard endpoints 4 to 7, the drill-down, and the run log that reads the same column. */
    private static Map<String, Supplier<String>> perDayQueries() {
        QueryService queries = new QueryService();
        MessageQSearchDto oneDay = new MessageQSearchDto();
        oneDay.setFromDate("2026-06-10");
        oneDay.setToDate("2026-06-10");
        Map<String, Supplier<String>> built = new LinkedHashMap<>();
        built.put("weeklyRunningJobStatistics", () -> queries.weeklyRunningJobStatistics("2026-06-08", "2026-06-14"));
        built.put("weeklyHrsRunningJobStatistics", () -> queries.weeklyHrsRunningJobStatistics("2026-06-08", "2026-06-14"));
        built.put("weeklyHrRunningStatisticsDimension", () -> queries.weeklyHrRunningStatisticsDimension("2026-06-10", 14L));
        built.put("weeklyHrRunningStatisticsDimensionDetail",
            () -> queries.weeklyHrRunningStatisticsDimensionDetail("2026-06-10", 14L, "Completed", null));
        built.put("fetchJobQLog", () -> queries.fetchJobQLog(oneDay, false));
        return built;
    }

    @Test
    void theDashboardsPerDayReadsUseTheDayIndex() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        List<String> unindexed = new ArrayList<>();
        perDayQueries().forEach((name, query) -> {
            String plan = String.join("\n", db.sql().queryForList("EXPLAIN " + query.get(), String.class));
            System.out.println("-- " + name + "\n" + plan);
            if (!plan.contains("idx_job_queue_date_created_day")) {
                unindexed.add(name);
            }
        });
        assertThat(unindexed).as("per-day Dashboard reads not using the day index").isEmpty();
    }

    @Test
    void everyIndexAnEntityDeclaresExistsInABuiltDatabase() throws Exception {
        List<String> missing = new ArrayList<>();
        File pojo = new File("src/main/java/process/model/pojo");
        for (File source : pojo.listFiles((dir, file) -> file.endsWith(".java"))) {
            Class<?> type = Class.forName("process.model.pojo." + source.getName().replace(".java", ""));
            Table table = type.getAnnotation(Table.class);
            if (type.getAnnotation(Entity.class) == null || table == null) {
                continue;
            }
            for (Index index : table.indexes()) {
                String columns = index.columnList().replace(" ", "");
                Integer found = db.sql().queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = ? "
                    + "AND indexname = ? AND replace(indexdef, ' ', '') LIKE ?", Integer.class, table.name(), index.name(), "%(" + columns + ")%");
                if (found == 0) {
                    missing.add(table.name() + "." + index.name() + "(" + columns + ")");
                }
            }
        }
        assertThat(missing).isEmpty();
    }
}
