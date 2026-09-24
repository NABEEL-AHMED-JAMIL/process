package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import process.model.dto.MessageQSearchDto;
import process.model.dto.SearchTextDto;
import process.security.TenantContext;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueryService's public surface, written down where a change to it fails the build (MIG-101, contradiction
 * B4; the inventory MIG-82 asked for before the Core/Analytics/Identity cut).
 *
 * The coverage audit counted 14 public methods; there are 16, plus toString. It missed the second
 * fetchAllLinkJobsWithSourceTaskQuery overload and both executeQuery overloads -- two of the three
 * executors, and so the class's whole logging exposure (DEF-142, fixed by MIG-74). Three execute; the
 * other 13 build SQL, and every one of the 13 carries tenantClause, the only tenant isolation these
 * native queries have.
 *
 * Each builder is listed with the tables it reads, taken from the SQL it actually builds, and its caller.
 * These joins are where a service boundary may and may not fall: job_queue and source_job are Core's,
 * app_user and tenant are Identity's and no builder reads them any more (MIG-107), and lookup_data was
 * decomposed and retired (MIG-167): home page and group names come from Core's task_reference. Adding, removing or
 * re-joining a method fails here until this list -- the documentation -- is updated with it.
 */
class QueryServiceSurfaceTest {

    private static final long TENANT = 2901L;

    /** The three executors: they run what the builders build, and are what logs it. */
    private static final Set<String> EXECUTORS = new TreeSet<>(Arrays.asList(
        "executeQueryForSingleResult(String)", "executeQuery(String)", "executeQuery(String,Pageable)"));

    private final QueryService queries = new QueryService();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Builder -> the tables its SQL reads. Callers: see the comment on each line. */
    private Map<String, Builder> builders() {
        SearchTextDto search = new SearchTextDto();
        search.setItemName("task_name");
        search.setItemValue("x");
        MessageQSearchDto runs = new MessageQSearchDto();
        runs.setFromDate("2026-09-01");
        runs.setToDate("2026-09-21");
        Map<String, Builder> builders = new LinkedHashMap<>();
        // SourceTaskServiceImpl: the task list and its count. task_reference resolves home page and group names.
        builders.put("listSourceTaskQuery(boolean,String,String,String,String,SearchTextDto)", new Builder(
            () -> this.queries.listSourceTaskQuery(false, null, null, null, null, search),
            "source_job", "source_task", "source_task_type", "task_reference"));
        // SourceTaskServiceImpl: a task's linked jobs, unordered and ordered.
        builders.put("fetchAllLinkJobsWithSourceTaskQuery(boolean,Long,String,String,SearchTextDto)", new Builder(
            () -> this.queries.fetchAllLinkJobsWithSourceTaskQuery(false, 7L, null, null, search), "source_job", "source_task"));
        builders.put("fetchAllLinkJobsWithSourceTaskQuery(boolean,Long,String,String,String,String,SearchTextDto)", new Builder(
            () -> this.queries.fetchAllLinkJobsWithSourceTaskQuery(false, 7L, null, null, "sj.job_id", "asc", search),
            "source_job", "source_task"));
        // DashboardServiceImpl, /dashboard.json: the seven endpoints and the drill-down's per-job totals.
        builders.put("jobStatusStatistics(String,String)", new Builder(
            () -> this.queries.jobStatusStatistics("2026-09-01", "2026-09-21"), "source_job"));
        // The people are Identity's (IdentityPort.members, MIG-107); this counts the listed ones' work.
        builders.put("userStatistics(String,String,Collection)", new Builder(
            () -> this.queries.userStatistics("2026-09-01", "2026-09-21", Arrays.asList(42L, 43L)), "job_queue", "source_job"));
        builders.put("jobRunningStatistics(String,String)", new Builder(
            () -> this.queries.jobRunningStatistics("2026-09-01", "2026-09-21"), "source_job"));
        builders.put("weeklyRunningJobStatistics(String,String)", new Builder(
            () -> this.queries.weeklyRunningJobStatistics("2026-09-15", "2026-09-21"), "job_queue", "source_job"));
        builders.put("weeklyHrsRunningJobStatistics(String,String)", new Builder(
            () -> this.queries.weeklyHrsRunningJobStatistics("2026-09-15", "2026-09-21"), "job_queue", "source_job"));
        builders.put("weeklyHrRunningStatisticsDimension(String,Long)", new Builder(
            () -> this.queries.weeklyHrRunningStatisticsDimension("2026-09-21", 14L), "job_queue", "source_job"));
        builders.put("weeklyHrRunningStatisticsDimensionDetail(String,Long,String,Long)", new Builder(
            () -> this.queries.weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", 7L), "job_queue", "source_job"));
        builders.put("statisticsBySourceJobId(Long)", new Builder(
            () -> this.queries.statisticsBySourceJobId(7L), "job_queue", "source_job"));
        // ReportExportServiceImpl, /report.json/runs: one row per run, with its owner and workspace ids (named by Identity).
        builders.put("runReportRows(String,String)", new Builder(
            () -> this.queries.runReportRows("2026-09-01", "2026-09-21"),
            "job_audit_logs", "job_queue", "source_job", "source_task"));
        // MessageQServiceImpl: the run log and its per-status totals.
        builders.put("fetchJobQLog(MessageQSearchDto,boolean)", new Builder(
            () -> this.queries.fetchJobQLog(runs, false) + "\n" + this.queries.fetchJobQLog(runs, true), "job_queue", "source_job"));
        return builders;
    }

    @Test
    void sixteenPublicMethodsThreeExecutorsAndThirteenBuilders() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        Set<String> declared = Arrays.stream(QueryService.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !"toString".equals(m.getName()))
            .map(QueryServiceSurfaceTest::signature).collect(Collectors.toCollection(TreeSet::new));
        Set<String> documented = new TreeSet<>(EXECUTORS);
        documented.addAll(this.builders().keySet());

        assertThat(declared).as("QueryService's public methods; update this test's list with the change").isEqualTo(documented);
        assertThat(declared).hasSize(16);
        assertThat(this.builders()).hasSize(13);
    }

    @Test
    void everyBuilderReadsExactlyTheTablesDocumentedForIt() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        this.builders().forEach((method, builder) ->
            assertThat(tables(builder.sql.get())).as(method).containsExactlyInAnyOrderElementsOf(builder.tables));
    }

    @Test
    void everyBuilderCarriesTheCallersTenant() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        this.builders().forEach((method, builder) ->
            assertThat(builder.sql.get()).as(method).containsPattern("tenant_id = " + TENANT + "\\b"));
    }

    @Test
    void everyBuilderGivesACallerWithNoTenantNothing() {
        TenantContext.set(null, "TENANT_ADMIN", 42L, "stray@nowhere.test");
        this.builders().forEach((method, builder) ->
            assertThat(builder.sql.get()).as(method).contains("and 1 = 0").doesNotContainPattern("tenant_id = \\d"));
    }

    private static Set<String> tables(String sql) {
        Set<String> tables = new TreeSet<>();
        Matcher matcher = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_]+)\\b").matcher(sql);
        while (matcher.find()) {
            // extract(hour from cast(...)) is a FROM that names no table.
            if (!"cast".equalsIgnoreCase(matcher.group(1))) {
                tables.add(matcher.group(1).toLowerCase());
            }
        }
        return tables;
    }

    private static String signature(Method method) {
        return method.getName() + "(" + Arrays.stream(method.getParameterTypes()).map(type -> type == Pageable.class
            ? "Pageable" : type.getSimpleName()).collect(Collectors.joining(",")) + ")";
    }

    private static final class Builder {

        private final Supplier<String> sql;
        private final Set<String> tables;

        Builder(Supplier<String> sql, String... tables) {
            this.sql = sql;
            this.tables = new TreeSet<>(Arrays.asList(tables));
        }
    }
}
