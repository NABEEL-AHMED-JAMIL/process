package process.model.service.impl;

import java.util.Collections;
import java.util.Arrays;
import process.util.RequestRefused;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import process.security.TenantContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-user statistics query and the run report's, checked where it matters. Since MIG-107 who is
 * listed is Identity's answer (IdentityPort.members, scoped there -- DashboardScopeTest) and the query only
 * counts the listed people's work; the report carries owner and workspace ids that Identity names
 * (ReportRunNamesTest). Neither reads app_user or tenant.
 *
 * The SQL is asserted as a string rather than executed. That is deliberate -- the isolation is
 * a property of the text this builder produces, and a test that runs it would pass just as
 * happily against a database with one tenant in it.
 */
public class UserStatisticsQueryTest {

    private final QueryService queryService = new QueryService();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // MIG-107: who is listed is Identity's answer (IdentityPort.members, scoped there -- see
    // DashboardScopeTest); this query only counts their work, for the ids it is handed.

    @Test
    @DisplayName("the counts are asked for exactly the people listed")
    void countsOnlyThePeopleHandedIn() {
        String sql = queryService.userStatistics(null, null, Arrays.asList(42L, 7L));
        assertTrue(sql.contains("sj.assigned_user_id in (7, 42)"), sql);
        assertTrue(sql.contains("group by sj.assigned_user_id"), sql);
    }

    @Test
    @DisplayName("nobody to count is a query that matches nothing, never every row")
    void nobodyMatchesNothing() {
        // A caller with a tenant, so the only "1 = 0" can be the people clause's.
        TenantContext.set(1004L, "TENANT_ADMIN", 42L, "ops@tenant.test");
        assertTrue(queryService.userStatistics(null, null, Collections.emptyList()).contains(" and 1 = 0 "));
        assertTrue(queryService.userStatistics(null, null, null).contains(" and 1 = 0 "));
    }

    @Test
    @DisplayName("the statistics query reads Core's tables only")
    void readsNoIdentityTable() {
        String sql = queryService.userStatistics("2026-08-01", "2026-08-24", Collections.singletonList(42L)).toLowerCase();
        assertFalse(sql.contains("app_user"), sql);
        assertFalse(sql.contains(" tenant "), sql);
    }

    @Test
    @DisplayName("runs are counted with a LEFT join, so a person's jobs without runs still count")
    void keepsJobsWithoutRuns() {
        String sql = queryService.userStatistics(null, null, Collections.singletonList(42L)).toLowerCase();
        assertTrue(sql.contains("left join job_queue"), sql);
        assertFalse(sql.contains("inner join job_queue"), sql);
    }

    @Test
    @DisplayName("the date range filters the runs, not the jobs")
    void dateFilterAppliesToRuns() {
        String sql = queryService.userStatistics("2026-08-01", "2026-08-24", Collections.singletonList(42L));
        int joinAt = sql.indexOf("left join job_queue");
        int whereAt = sql.indexOf("where sj.job_status in");
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
    @DisplayName("a platform administrator sees every tenant's runs")
    void reportUnscopedForPlatformAdmin() {
        TenantContext.set(1000L, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        String sql = queryService.runReportRows(null, null);
        // Matches the FILTER tenantClause emits (" and sj.tenant_id = 1004 "), not any mention
        // of the column. A bare "tenant_id =" also matches the join that carries the workspace
        // NAME onto every row -- which a platform admin needs precisely because their report is
        // unscoped and would otherwise merge every workspace with nothing to say it had.
        assertFalse(sql.contains("and sj.tenant_id ="),
            "a platform administrator's report must not be tenant-filtered: " + sql);
    }

    @Test
    @DisplayName("every run row carries the workspace it belongs to -- its id; the name is Identity's (MIG-107)")
    void reportRowsCarryTenant() {
        TenantContext.set(1000L, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        String sql = queryService.runReportRows(null, null);
        assertTrue(sql.contains("sj.tenant_id as tenant_id"), "the payload needs a workspace column: " + sql);
        assertFalse(sql.contains("join tenant"), "the workspace's name comes from Identity, not a join: " + sql);
    }

    @Test
    @DisplayName("skipped and missed runs are report rows too, dated by when they were due")
    void skippedAndMissedRunsAreIncluded() {
        String sql = queryService.runReportRows("2026-09-01", "2026-09-30");
        // Skip and Missed rows carry skip_time and no start_time; a filter on start_time alone
        // made a task skipped six times look like six fewer runs.
        assertTrue(sql.contains("where (q.start_time is not null or q.skip_time is not null)"), sql);
        assertFalse(sql.contains("where q.start_time is not null and"), sql);
        assertTrue(sql.contains("date(coalesce(q.start_time, q.skip_time)) between '2026-09-01' and '2026-09-30'"), sql);
        assertTrue(sql.contains("to_char(coalesce(q.start_time, q.skip_time), 'YYYY-MM-DD') as day"), sql);
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
        // The owner is the job's assignee id; Identity names them (ReportRunNamesTest), a gone one "Unassigned".
        assertTrue(sql.contains("sj.assigned_user_id as owner_id"), sql);
        assertFalse(sql.contains("app_user"), sql);
    }

    @Test
    @DisplayName("a malformed range is refused, never interpolated into the report query (MIG-103: refused, no longer silently dropped)")
    void reportRejectsMalformedDates() {
        assertThrows(RequestRefused.class, () -> queryService.runReportRows("2026-01-01'; drop table job_queue; --", "x"));
    }

    @Test
    @DisplayName("a malformed date is refused, never interpolated (MIG-103: refused, no longer silently dropped)")
    void rejectsMalformedDates() {
        assertThrows(RequestRefused.class, () -> queryService.userStatistics("2026-08-01'; drop table app_user; --", "nonsense", Collections.singletonList(1L)));
    }
}
