package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import process.api.DashboardRestApi;
import process.model.dto.JobStatusStatisticDto;
import process.model.dto.ResponseDto;
import process.model.dto.UserStatisticDto;
import process.model.dto.WeeklyHrJobDimensionStatisticsDto;
import process.model.dto.WeeklyJobStatisticsDto;
import process.security.TenantContext;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-46 (DEF-128): the Dashboard said nothing about whose numbers it showed. A platform admin's
 * tiles merged every workspace into one set of totals with no sign of it, and userStatistics -- open
 * to the lowest role -- listed every colleague's failed-run count. Now every Dashboard DTO carries
 * its tenant: a per-job or per-user row names its own workspace, and a total says whether it is one
 * workspace's or all of them. A tenant user's userStatistics is their own row. The arithmetic is
 * untouched: these check the labels travel with the same numbers.
 */
class DashboardScopeTest {

    private static final long TENANT = 1004L;

    private final QueryService queries = mock(QueryService.class);
    private final DashboardServiceImpl dashboard = new DashboardServiceImpl(this.queries, null, null, null, null);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void rows(Object[]... rows) {
        when(this.queries.executeQuery((String) any())).thenReturn(new ArrayList<>(Arrays.asList(rows)));
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> data(Object response) {
        return (List<T>) ((ResponseDto) response).getData();
    }

    @Test
    void aPlatformAdminsTotalsSayTheyCoverEveryWorkspace() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        rows(new Object[] {"Active", 319}, new Object[] {"Inactive", 32});

        List<JobStatusStatisticDto> tiles = data(this.dashboard.jobStatusStatistics(null, null));

        assertThat(tiles).extracting(JobStatusStatisticDto::getValue).containsExactly(319, 32);
        assertThat(tiles).isNotEmpty().allSatisfy(t -> {
            assertThat(t.getAllWorkspaces()).isTrue();
            assertThat(t.getTenantId()).isNull();
        });
    }

    @Test
    void aWorkspacesTotalsNameTheWorkspace() throws Exception {
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@tenant.test");
        rows(new Object[] {"COMPLETED", 20});

        List<JobStatusStatisticDto> running = data(this.dashboard.jobRunningStatistics(null, null));
        assertThat(running).isNotEmpty().allSatisfy(t -> {
            assertThat(t.getTenantId()).isEqualTo(TENANT);
            assertThat(t.getAllWorkspaces()).isFalse();
        });

        rows(new Object[] {"Mon", 7});
        List<JobStatusStatisticDto> weekly = data(this.dashboard.weeklyRunningJobStatistics(null, null));
        assertThat(weekly).isNotEmpty().allSatisfy(t -> assertThat(t.getTenantId()).isEqualTo(TENANT));

        rows(new Object[] {"Monday", 9, "2026-09-21", 3});
        List<WeeklyJobStatisticsDto> hourly = data(this.dashboard.weeklyHrsRunningJobStatistics(null, null));
        assertThat(hourly).isNotEmpty().allSatisfy(t -> {
            assertThat(t.getCount()).isEqualTo(3L);
            assertThat(t.getTenantId()).isEqualTo(TENANT);
            assertThat(t.getAllWorkspaces()).isFalse();
        });
    }

    /** Per-job rows name the workspace the job is in; the TOTAL row says what it adds up. */
    @Test
    void anHoursJobsNameTheirOwnWorkspaces() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        rows(new Object[] {11, "orders", 0, 0, 0, 1, 2, 0, 0, 0, 0, 3, 1004},
            new Object[] {12, "claims", 0, 0, 0, 0, 5, 0, 0, 0, 0, 5, 1007},
            new Object[] {null, "TOTAL", 0, 0, 0, 1, 7, 0, 0, 0, 0, 8, null});

        List<WeeklyHrJobDimensionStatisticsDto> jobs = data(this.dashboard.weeklyHrRunningStatisticsDimension("2026-09-21", 9L));

        assertThat(jobs).extracting(WeeklyHrJobDimensionStatisticsDto::getTenantId).containsExactly(1004L, 1007L, null);
        assertThat(jobs).extracting(WeeklyHrJobDimensionStatisticsDto::getTotal).containsExactly(3L, 5L, 8L);
        assertThat(jobs.get(2).getAllWorkspaces()).as("the platform total spans workspaces").isTrue();
        assertThat(jobs.get(0).getAllWorkspaces()).as("a job is in one workspace").isFalse();
    }

    @Test
    void everyPersonsRowNamesTheirWorkspace() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        rows(new Object[] {42L, "ops", "Ops", "TENANT_ADMIN", "Active", null, null, 3, 2, 1, 10, 8, 2, 1004L});

        List<UserStatisticDto> people = data(this.dashboard.userStatistics(null, null));

        assertThat(people).hasSize(1);
        assertThat(people.get(0).getFailedCount()).isEqualTo(2);
        assertThat(people.get(0).getTenantId()).isEqualTo(1004L);
    }

    // ---- who may see whose people ----------------------------------------------------------------

    @Test
    void aTenantUserSeesOnlyTheirOwnRow() {
        TenantContext.set(TENANT, "TENANT_USER", 42L, "someone@tenant.test");
        String sql = new QueryService().userStatistics(null, null);
        assertThat(sql).contains("u.tenant_id = 1004").contains("u.app_user_id = 42");
    }

    @Test
    void aTenantUserWithNoUserIdSeesNobody() {
        TenantContext.set(TENANT, "TENANT_USER", null, "broken");
        assertThat(new QueryService().userStatistics(null, null)).contains("1 = 0");
    }

    @Test
    void administratorsStillSeeTheirWholeWorkspace() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@tenant.test");
        assertThat(new QueryService().userStatistics(null, null)).doesNotContain("u.app_user_id =");
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        assertThat(new QueryService().userStatistics(null, null)).doesNotContain("u.app_user_id =").doesNotContain("tenant_id =");
    }

    @Test
    void theQueriesCarryTheRowsWorkspace() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        QueryService real = new QueryService();
        assertThat(real.userStatistics(null, null)).contains("u.tenant_id from app_user").contains("u.avatar_key, u.tenant_id order by");
        assertThat(real.weeklyHrRunningStatisticsDimension("2026-09-21", 9L))
            .contains("source_job.tenant_id\n").contains("NULL AS tenant_id");
    }

    /**
     * The class rule is TENANT_USER for all seven, and a method-level @PreAuthorize REPLACES a class
     * one rather than adding to it -- so a method that grew its own rule could open itself wider.
     * None may have one, and all seven must still be reachable.
     */
    @Test
    void noEndpointEscapesTheClassRule() {
        PreAuthorize classRule = DashboardRestApi.class.getAnnotation(PreAuthorize.class);
        assertThat(classRule.value()).isEqualTo("hasRole('TENANT_USER')");
        List<String> endpoints = new ArrayList<>();
        for (Method m : DashboardRestApi.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.isAnnotationPresent(RequestMapping.class)) {
                endpoints.add(m.getName());
                assertThat(m.getAnnotation(PreAuthorize.class)).as(m.getName()).isNull();
            }
        }
        Collections.sort(endpoints);
        assertThat(endpoints).containsExactly("jobRunningStatistics", "jobStatusStatistics", "userStatistics",
            "weeklyHrRunningStatisticsDimension", "weeklyHrRunningStatisticsDimensionDetail", "weeklyHrsRunningJobStatistics",
            "weeklyRunningJobStatistics");
    }
}
