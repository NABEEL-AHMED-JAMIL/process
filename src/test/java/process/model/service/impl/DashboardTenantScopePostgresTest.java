package process.model.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * MIG-82: the job-statistics read surface (/dashboard.json) stays with Core, which owns the tables it
 * reads, and every one of its queries answers for the caller's workspace alone -- executed against
 * the real schema, not asserted on the SQL text.
 *
 * Two workspaces run jobs in the same Chicago hour: CareBridge (2901) two, Northwind (2902) three.
 * Each statistic, measured as the one number it adds up to, must be 2 for CareBridge, 3 for
 * Northwind, 5 for a platform admin (every workspace, audited) and 0 for a caller from any other
 * workspace or with none. userStatistics is pinned the same way in UserStatisticsPostgresTest.
 * Opt-in, like every ScratchEtlJob test.
 */
class DashboardTenantScopePostgresTest {

    private static final String DAY = "2026-09-21";
    private static final long HOUR = 14L;
    private static final long[] ALL_JOBS = {101L, 102L, 201L, 202L, 203L};

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("dashboard_scope");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES "
            + "(2901, 'Active', 'CHS', 'CareBridge'), (2902, 'Active', 'NWD', 'Northwind')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, tenant_id) VALUES "
            + "(101, '2026-09-21 09:00-05', 'Auto', 'claims', 'Active', 'Completed', 1, 2901), "
            + "(102, '2026-09-21 09:00-05', 'Auto', 'eligibility', 'Inactive', 'Failed', 1, 2901), "
            + "(201, '2026-09-21 09:00-05', 'Auto', 'orders', 'Active', 'Running', 1, 2902), "
            + "(202, '2026-09-21 09:00-05', 'Auto', 'invoices', 'Active', 'Completed', 1, 2902), "
            + "(203, '2026-09-21 09:00-05', 'Auto', 'returns', 'Active', 'Failed', 1, 2902)");
        // Every run in the same Chicago hour (14:00 CDT), so one query range covers both workspaces.
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES "
            + "(1001, '2026-09-21 14:03-05', 101, 'Completed', 'Active'), (1002, '2026-09-21 14:10-05', 102, 'Failed', 'Active'), "
            + "(2001, '2026-09-21 14:20-05', 201, 'Running', 'Active'), (2002, '2026-09-21 14:30-05', 202, 'Completed', 'Active'), "
            + "(2003, '2026-09-21 14:40-05', 203, 'Failed', 'Active')");
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

    @Test
    void aWorkspaceCountsItsOwnJobsAndRunsOnly() {
        TenantContext.set(2901L, "TENANT_USER", 42L, "ops@carebridge.test");
        assertThat(totals()).containsOnly(every(2));

        TenantContext.set(2902L, "TENANT_ADMIN", 43L, "ops@northwind.test");
        assertThat(totals()).containsOnly(every(3));
    }

    @Test
    void aPlatformAdminCountsEveryWorkspace() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@platform.test");

        assertThat(totals()).containsOnly(every(5));
    }

    @Test
    void anotherWorkspaceOrNoWorkspaceCountsNothing() {
        TenantContext.set(2999L, "TENANT_ADMIN", 44L, "ops@elsewhere.test");
        assertThat(totals()).containsOnly(every(0));

        TenantContext.set(null, "TENANT_USER", 45L, "nobody@nowhere.test");
        assertThat(totals()).containsOnly(every(0));
    }

    /** The drill-down on one job: another workspace's job id reads as no runs, not as its runs. */
    @Test
    void anotherWorkspacesJobReadsAsEmpty() {
        TenantContext.set(2901L, "TENANT_USER", 42L, "ops@carebridge.test");
        QueryService query = new QueryService();

        assertThat(count(db.sql().queryForList(query.statisticsBySourceJobId(201L)).get(0), "total")).isZero();
        assertThat(db.sql().queryForList(query.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, 201L))).isEmpty();
        assertThat(db.sql().queryForList(query.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, 101L))).hasSize(1);
    }

    /** Each statistic, reduced to the one number it adds up to, for whoever TenantContext says is asking. */
    private static Map<String, Long> totals() {
        QueryService query = new QueryService();
        JdbcTemplate sql = db.sql();
        Map<String, Long> totals = new LinkedHashMap<>();

        long all = 0;
        for (Map<String, Object> row : sql.queryForList(query.jobStatusStatistics(DAY, DAY))) {
            if ("All".equals(row.get("job_status"))) all = count(row, "total_count");
        }
        totals.put("jobStatusStatistics", all);
        totals.put("jobRunningStatistics", sum(sql.queryForList(query.jobRunningStatistics(DAY, DAY)), "total_count"));
        totals.put("weeklyRunningJobStatistics", sum(sql.queryForList(query.weeklyRunningJobStatistics(DAY, DAY)), "count"));
        totals.put("weeklyHrsRunningJobStatistics", sum(sql.queryForList(query.weeklyHrsRunningJobStatistics(DAY, DAY)), "count"));

        long hourTotal = 0;
        for (Map<String, Object> row : sql.queryForList(query.weeklyHrRunningStatisticsDimension(DAY, HOUR))) {
            if ("TOTAL".equals(row.get("job_name"))) hourTotal = count(row, "total");
        }
        totals.put("weeklyHrRunningStatisticsDimension", hourTotal);
        totals.put("weeklyHrRunningStatisticsDimensionDetail",
            (long) sql.queryForList(query.weeklyHrRunningStatisticsDimensionDetail(DAY, HOUR, null, null)).size());

        long byJob = 0;
        for (long jobId : ALL_JOBS) {
            byJob += sum(sql.queryForList(query.statisticsBySourceJobId(jobId)), "total");
        }
        totals.put("statisticsBySourceJobId", byJob);
        return totals;
    }

    @SuppressWarnings("unchecked")
    private static Map.Entry<String, Long>[] every(long expected) {
        String[] names = {"jobStatusStatistics", "jobRunningStatistics", "weeklyRunningJobStatistics",
            "weeklyHrsRunningJobStatistics", "weeklyHrRunningStatisticsDimension", "weeklyHrRunningStatisticsDimensionDetail",
            "statisticsBySourceJobId"};
        Map.Entry<String, Long>[] entries = new Map.Entry[names.length];
        for (int i = 0; i < names.length; i++) entries[i] = entry(names[i], expected);
        return entries;
    }

    private static long sum(List<Map<String, Object>> rows, String column) {
        long total = 0;
        for (Map<String, Object> row : rows) total += count(row, column);
        return total;
    }

    private static long count(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).longValue();
    }
}
