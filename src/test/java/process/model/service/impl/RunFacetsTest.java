package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.model.dto.MessageQSearchDto;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UX review 2026-10-13 (B3): the Runs explorer's facets and histogram -- the answer's shape from the query's rows, the
 * slot size, the empty slots, the triggers' keys and labels, and what the query asks for. The SQL itself runs on a real
 * changelog in RunFacetsPostgresTest.
 */
class RunFacetsTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static MessageQSearchDto search(String from, String to) {
        MessageQSearchDto search = new MessageQSearchDto();
        search.setFromDate(from);
        search.setToDate(to);
        return search;
    }

    private static Object[] row(String facet, Object key, String name, Object slot, long count) {
        return new Object[] {facet, key, name, slot, count};
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> answer, String name) {
        return (List<Map<String, Object>>) answer.get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(Map<String, Object> answer) {
        return (List<Map<String, Object>>) ((Map<String, Object>) answer.get("histogram")).get("buckets");
    }

    @Test
    void twoDaysOrLessAreHoursAndLongerRangesAreDays() {
        assertThat(RunFacets.bucketOf("2026-10-12", "2026-10-12")).isEqualTo(RunFacets.HOUR);
        assertThat(RunFacets.bucketOf("2026-10-12", "2026-10-13")).isEqualTo(RunFacets.HOUR);
        assertThat(RunFacets.bucketOf("2026-10-11", "2026-10-13")).isEqualTo(RunFacets.DAY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHistogramCoversTheWholeRangeEmptySlotsIncluded() {
        List<Object[]> rows = Arrays.asList(
            row("slot", "Completed", null, "2026-10-08T00:00:00", 3),
            row("slot", "FAILED", null, "2026-10-08T00:00:00", 1),
            row("slot", "Completed", null, "2026-10-13T00:00:00", 2));
        Map<String, Object> answer = RunFacets.assemble(rows, search("2026-10-07", "2026-10-13"), RunFacets.DAY);

        assertThat(answer).containsEntry("total", 6L);
        assertThat(buckets(answer)).extracting(b -> b.get("start") + "=" + b.get("total")).containsExactly(
            "2026-10-07T00:00:00=0", "2026-10-08T00:00:00=4", "2026-10-09T00:00:00=0", "2026-10-10T00:00:00=0",
            "2026-10-11T00:00:00=0", "2026-10-12T00:00:00=0", "2026-10-13T00:00:00=2");
        assertThat((Map<String, Long>) buckets(answer).get(1).get("counts")).containsEntry("Completed", 3L).containsEntry("Failed", 1L);

        Map<String, Object> day = RunFacets.assemble(Collections.singletonList(
            row("slot", "Running", null, "2026-10-13T17:00:00", 1)), search("2026-10-13", "2026-10-13"), RunFacets.HOUR);
        assertThat(buckets(day)).hasSize(24);
        assertThat(buckets(day).get(17)).containsEntry("start", "2026-10-13T17:00:00").containsEntry("total", 1L);
    }

    @Test
    void facetsAreCountedAndWhatAFilterAskedForIsListedAtZero() {
        MessageQSearchDto asked = search("2026-10-07", "2026-10-13");
        asked.setJobStatuses(new HashSet<>(Arrays.asList(JobStatus.Completed, JobStatus.Interrupt)));
        asked.setTaskDetailIds(new HashSet<>(Arrays.asList(1932L, 4040L)));
        List<Object[]> rows = Arrays.asList(
            row("status", "Completed", null, null, 12), row("status", "Failed", null, null, 3),
            row("pipeline", "1932", "Patient 360", null, 9), row("pipeline", "1928", "Brain MRI grading", null, 6),
            row("trigger", "manual", null, null, 10), row("trigger", "file", null, null, 5));
        Map<String, Object> answer = RunFacets.assemble(rows, asked, RunFacets.DAY);

        assertThat(list(answer, "statuses")).extracting(s -> s.get("key") + "=" + s.get("count"))
            .containsExactly("Completed=12", "Failed=3", "Interrupt=0");
        assertThat(list(answer, "pipelines")).extracting(p -> p.get("taskDetailId") + ":" + p.get("taskName") + "=" + p.get("count"))
            .containsExactly("1932:Patient 360=9", "1928:Brain MRI grading=6", "4040:null=0");
        assertThat(list(answer, "triggers")).extracting(t -> t.get("key") + ":" + t.get("label") + "=" + t.get("count")).containsExactly(
            "schedule:Schedule=0", "manual:Run now=10", "file:File arrived=5", "document:Document approved=0", "form:Form submitted=0",
            "backfill:Backfill=0", "api:API=0", "retry:Retry=0");
    }

    @Test
    void triggerFiltersTakeKnownKeysOnlyAndAnUnknownOneMatchesNothing() {
        assertThat(RunFacets.triggerList(null)).isNull();
        assertThat(RunFacets.triggerList(Collections.emptySet())).isNull();
        assertThat(RunFacets.triggerList(Arrays.asList("Manual", " file ", "manual"))).isEqualTo("'manual', 'file'");
        assertThat(RunFacets.triggerList(Collections.singletonList("x'); drop table job_queue; --"))).isEqualTo("null");
        assertThat(RunFacets.triggerSql("jq")).contains("jq.attempt > 1").contains("api_intake").contains("document_approval")
            .contains("inbox_arrival").contains("jq.run_manual");
    }

    @Test
    void theFacetQueryIsTheCallersRunsOfTheRangeGroupedInTheDatabase() {
        TenantContext.set(2960L, "TENANT_USER", 4641L, "reviewer@riverside.test");
        MessageQSearchDto asked = search("2026-10-07", "2026-10-13");
        asked.setJobStatuses(new HashSet<>(Collections.singletonList(JobStatus.Failed)));
        asked.setTaskDetailIds(new HashSet<>(Collections.singletonList(1932L)));
        asked.setTriggers(new HashSet<>(Collections.singletonList("manual")));
        String sql = new QueryService().runFacets(asked, RunFacets.DAY);

        assertThat(sql).contains("between '2026-10-07' and '2026-10-13'").contains("sj.tenant_id = 2960")
            .contains("sj.created_by = 4641 or sj.assigned_user_id = 4641").contains("date_trunc('day'")
            .contains("group by r.status").contains("group by r.tdi").contains("group by r.trig").contains("group by r.slot, r.status");
        // Each facet leaves its own filter out: status counts carry the pipeline and trigger filters, not the status one.
        String statusPart = sql.substring(sql.indexOf("select 'status'"), sql.indexOf("union all select 'pipeline'"));
        assertThat(statusPart).contains("r.tdi in (1932)").contains("r.trig in ('manual')").doesNotContain("UPPER(r.status)");
        String pipelinePart = sql.substring(sql.indexOf("union all select 'pipeline'"), sql.indexOf("union all select 'trigger'"));
        assertThat(pipelinePart).contains("UPPER(r.status) in ('FAILED')").contains("r.trig in ('manual')").doesNotContain("r.tdi in");
        String triggerPart = sql.substring(sql.indexOf("union all select 'trigger'"), sql.indexOf("union all select 'slot'"));
        assertThat(triggerPart).contains("UPPER(r.status) in ('FAILED')").contains("r.tdi in (1932)").doesNotContain("r.trig in");
        String slotPart = sql.substring(sql.indexOf("union all select 'slot'"));
        assertThat(slotPart).contains("UPPER(r.status) in ('FAILED')").contains("r.tdi in (1932)").contains("r.trig in ('manual')");
    }

    /** The builders run as JPA native queries, whose parser takes ":name" for a parameter: no "::" casts outside quotes. */
    @Test
    void theNewQueriesHaveNoColonCastsJpaWouldTakeForParameters() {
        TenantContext.set(2960L, "TENANT_ADMIN", 4640L, "admin@riverside.test");
        MessageQSearchDto asked = search("2026-10-07", "2026-10-13");
        asked.setTriggers(new HashSet<>(Collections.singletonList("file")));
        QueryService queries = new QueryService();
        for (String sql : Arrays.asList(queries.runFacets(asked, RunFacets.HOUR), queries.fetchJobQLog(asked, false),
            queries.fetchJobQLog(asked, true), queries.lastRunsOfTasks(Arrays.asList(1L, 2L)))) {
            assertThat(sql.replaceAll("'[^']*'", "''")).as(sql).doesNotContain("::");
        }
    }

    @Test
    void fetchLogsNarrowsByPipelineAndTriggerAndNamesThemOnEachRow() {
        TenantContext.set(2960L, "TENANT_ADMIN", 4640L, "admin@riverside.test");
        MessageQSearchDto asked = search("2026-10-07", "2026-10-13");
        asked.setTaskDetailIds(new HashSet<>(Collections.singletonList(1932L)));
        asked.setTriggers(new HashSet<>(Collections.singletonList("file")));
        QueryService queries = new QueryService();
        String rows = queries.fetchJobQLog(asked, false);
        assertThat(rows).contains("sj.task_detail_id, st.task_name, ").contains("as run_trigger")
            .contains("left join source_task st on st.task_detail_id = sj.task_detail_id")
            .contains("and sj.task_detail_id in (1932)").contains("in ('file')");
        assertThat(queries.fetchJobQLog(asked, true)).as("the counts narrow the same way")
            .contains("and sj.task_detail_id in (1932)").contains("in ('file')");
        assertThat(queries.fetchJobQLog(search("2026-10-07", "2026-10-13"), false)).as("no new filter unless asked")
            .doesNotContain("task_detail_id in").doesNotContain(") in (");
    }

    @Test
    void theServiceRefusesAMissingOrBackwardsOrOverlongRangeBeforeAnyQuery() {
        QueryService queries = mock(QueryService.class);
        MessageQServiceImpl service = new MessageQServiceImpl(null, queries, null, null, null);
        assertThat(service.runFacets(search(null, "2026-10-13")).getMessage()).isEqualTo("FromDate missing.");
        assertThat(service.runFacets(search("2026-10-13", null)).getMessage()).isEqualTo("ToDate missing.");
        assertThat(service.runFacets(search("2026-10-14", "2026-10-13")).getMessage()).isEqualTo("ToDate is before FromDate.");
        assertThat(service.runFacets(search("13/10/2026", "2026-10-13")).getMessage()).contains("yyyy-MM-dd");
        assertThat(service.runFacets(search("2024-01-01", "2026-10-13")).getMessage()).contains("at most 366 days");
        verify(queries, never()).executeQuery(anyString());

        when(queries.runFacets(any(), anyString())).thenReturn("select");
        when(queries.executeQuery("select")).thenReturn(new ArrayList<>(Collections.singletonList(
            row("slot", "Completed", null, "2026-10-13T09:00:00", 2))));
        ResponseDto answer = service.runFacets(search("2026-10-12", "2026-10-13"));
        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) answer.getData();
        assertThat(data).containsEntry("total", 2L);
        assertThat(((Map<?, ?>) data.get("histogram")).get("bucket")).isEqualTo("hour");
        assertThat(buckets(data)).hasSize(48);
    }
}
