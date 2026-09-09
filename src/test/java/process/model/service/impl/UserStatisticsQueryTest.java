package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import process.security.TenantContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-user statistics query, checked where it matters: that a tenant user cannot be shown
 * another tenant's people, and that the aggregate still counts users who own nothing.
 *
 * The SQL is asserted as a string rather than executed. That is deliberate -- the isolation is
 * a property of the text this builder produces, and a test that runs it would pass just as
 * happily against a database with one tenant in it.
 */
class UserStatisticsQueryTest {

    private final QueryService queryService = new QueryService();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a tenant user's query is scoped to their own tenant")
    void scopedForTenantUser() {
        TenantContext.set(1004L, "TENANT_USER", 42L, "someone@tenant.test");
        String sql = queryService.userStatistics(null, null);
        assertTrue(sql.contains("u.tenant_id = 1004"),
            "the tenant filter must name the caller's tenant: " + sql);
    }

    @Test
    @DisplayName("a platform admin sees every tenant")
    void unscopedForPlatformAdmin() {
        TenantContext.set(1000L, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        String sql = queryService.userStatistics(null, null);
        assertFalse(sql.contains("tenant_id ="),
            "a platform admin must not be filtered to one tenant: " + sql);
    }

    @Test
    @DisplayName("no tenant in context is treated as unscoped rather than tenant zero")
    void noTenantInContext() {
        String sql = queryService.userStatistics(null, null);
        assertFalse(sql.contains("tenant_id = 0"), sql);
    }

    @Test
    @DisplayName("users who own nothing are still counted, so the joins stay LEFT")
    void keepsUsersWithoutJobs() {
        String sql = queryService.userStatistics(null, null).toLowerCase();
        assertTrue(sql.contains("left join source_job"), sql);
        assertTrue(sql.contains("left join job_queue"), sql);
        assertFalse(sql.contains("inner join source_job"),
            "an inner join would drop every user who has not been given work yet");
    }

    @Test
    @DisplayName("the date range filters the runs, not the users")
    void dateFilterAppliesToRuns() {
        String sql = queryService.userStatistics("2026-08-01", "2026-08-24");
        int joinAt = sql.indexOf("left join job_queue");
        int whereAt = sql.indexOf("where u.status");
        int filterAt = sql.indexOf("date(jq.date_created)");
        assertTrue(filterAt > joinAt && filterAt < whereAt,
            "the range belongs in the join, or the left join collapses to an inner one: " + sql);
    }

    // ---- the report's own query ---------------------------------------------------------

    @Test
    @DisplayName("report rows are scoped to the caller's tenant")
    void reportScopedForTenantUser() {
        TenantContext.set(1004L, "TENANT_USER", 42L, "someone@tenant.test");
        String sql = queryService.runReportRows(null, null);
        assertTrue(sql.contains("sj.tenant_id = 1004"),
            "a tenant user must not see another tenant's runs: " + sql);
    }

    @Test
    @DisplayName("a platform admin sees every tenant's runs")
    void reportUnscopedForPlatformAdmin() {
        TenantContext.set(1000L, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        String sql = queryService.runReportRows(null, null);
        // Matches the FILTER tenantClause emits (" and sj.tenant_id = 1004 "), not any mention
        // of the column. A bare "tenant_id =" also matches the join that carries the workspace
        // NAME onto every row -- which a platform admin needs precisely because their report is
        // unscoped and would otherwise merge every workspace with nothing to say it had.
        assertFalse(sql.contains("and sj.tenant_id ="),
            "a platform admin's report must not be tenant-filtered: " + sql);
    }

    @Test
    @DisplayName("every run row carries the workspace it belongs to")
    void reportRowsCarryTenantName() {
        TenantContext.set(1000L, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        String sql = queryService.runReportRows(null, null);
        assertTrue(sql.contains("as tenant"), "the payload needs a workspace column: " + sql);
        assertTrue(sql.contains("left join tenant t on t.tenant_id = sj.tenant_id"),
            "the workspace name has to be joined, not inferred: " + sql);
    }

    @Test
    @DisplayName("a run with no end time reports -1 rather than a duration of zero")
    void unfinishedRunsAreNotZero() {
        String sql = queryService.runReportRows(null, null);
        assertTrue(sql.contains("when q.end_time is null then -1"), sql);
    }

    @Test
    @DisplayName("a task or owner that is gone does not drop the run")
    void keepsRunsWithoutTaskOrOwner() {
        String sql = queryService.runReportRows(null, null).toLowerCase();
        assertTrue(sql.contains("left join source_task"), sql);
        assertTrue(sql.contains("left join app_user"), sql);
    }

    @Test
    @DisplayName("a malformed range is dropped rather than interpolated into the report query")
    void reportRejectsMalformedDates() {
        String sql = queryService.runReportRows("2026-01-01'; drop table job_queue; --", "x");
        assertFalse(sql.contains("drop table"), sql);
        assertFalse(sql.contains("date(q.start_time)"), sql);
    }

    @Test
    @DisplayName("a malformed date is dropped rather than interpolated")
    void rejectsMalformedDates() {
        String sql = queryService.userStatistics("2026-08-01'; drop table app_user; --", "nonsense");
        assertFalse(sql.contains("drop table"), sql);
        assertFalse(sql.contains("date(jq.date_created)"), sql);
    }
}
