package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.api.MessageQRestApi;
import process.api.SourceJobRestApi;
import process.config.GlobalExceptionHandler;
import process.model.dto.MessageQSearchDto;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.service.SourceJobService;
import process.security.TenantContext;
import process.util.ListSort;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner 2026-10-27 ("add a sort on each table"): a list of runs -- a schedule's history and Runs › Latest -- sorts by Run,
 * Queued, Started, Ended, Duration and Status, from SQL written in RunListSort. An unknown column or direction is a 400 naming
 * the six; no sort is the newest-first read, unchanged.
 */
class RunListSortTest {

    private static final String ALLOWED = "sort must be one of: run, queued, started, ended, duration, status.";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void everyColumnHasItsSqlBothWaysWithEmptyValuesLastAndTheRunAsTiebreak() {
        assertThat(RunListSort.orderBy(RunListSort.parse("queued", "desc"))).isEqualTo("jq.date_created DESC, jq.job_queue_id DESC");
        assertThat(RunListSort.orderBy(RunListSort.parse("started", "asc")))
            .isEqualTo("(COALESCE(jq.start_time, jq.skip_time)) ASC NULLS LAST, jq.job_queue_id ASC");
        assertThat(RunListSort.orderBy(RunListSort.parse("ended", "desc"))).isEqualTo("jq.end_time DESC NULLS LAST, jq.job_queue_id DESC");
        assertThat(RunListSort.orderBy(RunListSort.parse("duration", "desc")))
            .isEqualTo("(jq.end_time - jq.start_time) DESC NULLS LAST, jq.job_queue_id DESC");
        assertThat(RunListSort.orderBy(RunListSort.parse("run", "asc"))).isEqualTo("jq.job_queue_id ASC");
        assertThat(RunListSort.orderBy(RunListSort.parse("status", "asc"))).startsWith("(CASE jq.job_status WHEN 'Failed' THEN 0 ")
            .endsWith(" ASC, jq.job_queue_id ASC");
        assertThat(RunListSort.jobHistory(RunListSort.parse("ended", "asc")))
            .isEqualTo("select jq.* from job_queue jq where jq.job_id = :jobId order by jq.end_time ASC NULLS LAST, jq.job_queue_id ASC "
                + "limit :limit offset :offset");
    }

    @Test
    void theStatusOrderNamesEveryStatusProblemsFirst() {
        assertThat(EnumSet.copyOf(RunListSort.STATUS_ORDER)).isEqualTo(EnumSet.allOf(JobStatus.class));
        assertThat(RunListSort.STATUS_ORDER).startsWith(JobStatus.Failed, JobStatus.Interrupt).endsWith(JobStatus.Completed);
    }

    @Test
    void anUnknownColumnOrDirectionIsRefusedNamingTheColumns() {
        assertThatThrownBy(() -> RunListSort.parse("message", "asc")).isInstanceOf(ListSort.Refused.class).hasMessage(ALLOWED);
        assertThatThrownBy(() -> RunListSort.parse("started", "newest")).isInstanceOf(ListSort.Refused.class)
            .hasMessage("dir must be asc or desc.");
        assertThat(RunListSort.parse(null, null)).isNull();
    }

    @Test
    void runsLatestOrdersItsWindowByTheSortAndKeepsNewestFirstWithout() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@riverside.test");
        QueryService queries = new QueryService();
        MessageQSearchDto search = new MessageQSearchDto("2026-06-08", "2026-06-14", null, null, null);
        search.setLimit(200);
        assertThat(queries.fetchJobQLog(search, false)).contains("order by job_queue_id desc limit 201 offset 0");
        search.setSort("duration");
        search.setDir("desc");
        search.setPage(1);
        assertThat(queries.fetchJobQLog(search, false))
            .contains("order by (jq.end_time - jq.start_time) DESC NULLS LAST, jq.job_queue_id DESC limit 201 offset 200");
        assertThat(queries.fetchJobQLog(search, true)).as("the counts are not ordered").doesNotContain("order by");
        search.setSort("jq.job_status; select 1");
        assertThatThrownBy(() -> queries.fetchJobQLog(search, false)).isInstanceOf(ListSort.Refused.class).hasMessage(ALLOWED);
    }

    @Test
    void overHttpRunsLatestRefusesAnUnknownSortWithA400() throws Exception {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@riverside.test");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MessageQRestApi(new MessageQServiceImpl(null, new QueryService(), null, null, null)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/message.json/fetchLogs").contentType("application/json")
                .content("{\"fromDate\": \"2026-06-08\", \"toDate\": \"2026-06-14\", \"limit\": 200, \"sort\": \"message\", \"dir\": \"asc\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value(ALLOWED));
    }

    @Test
    void overHttpAScheduleHistoryWithoutASortIsTheOldReadAndARefusedSortIsA400() throws Exception {
        SourceJobService service = mock(SourceJobService.class);
        when(service.fetchSourceJobQueueListWithJobId(anyLong(), any(), any())).thenReturn(new ResponseDto("SUCCESS", "ok"));
        when(service.fetchSourceJobQueueListWithJobId(anyLong(), any(), any(), eq("message"), any(), any())).thenThrow(new ListSort.Refused(ALLOWED));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceJobRestApi(service, null, null))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/sourceJob.json/fetchSourceJobQueueListWithJobId").param("jobId", "7").param("limit", "500").param("beforeId", "90"))
            .andExpect(status().isOk());
        verify(service).fetchSourceJobQueueListWithJobId(7L, 500, 90L);
        verify(service, never()).fetchSourceJobQueueListWithJobId(anyLong(), any(), any(), any(), any(), any());

        mvc.perform(get("/sourceJob.json/fetchSourceJobQueueListWithJobId").param("jobId", "7").param("sort", "message").param("dir", "asc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(ALLOWED));
    }

    @Test
    void aSortedHistoryPagesByOffsetNeverByTheNewestFirstCursor() {
        // The refusals come before any read: the service's stores are not there.
        SourceJobServiceImpl jobs = mock(SourceJobServiceImpl.class, Mockito.CALLS_REAL_METHODS);
        assertThatThrownBy(() -> jobs.fetchSourceJobQueueListWithJobId(7L, 500, 90L, "started", "desc", 0))
            .isInstanceOf(ListSort.Refused.class).hasMessage("beforeId pages the newest-first order: with a sort, page with offset.");
        assertThatThrownBy(() -> jobs.fetchSourceJobQueueListWithJobId(7L, 500, null, null, null, 500))
            .isInstanceOf(ListSort.Refused.class).hasMessageStartingWith("offset pages a sorted list");
        assertThatThrownBy(() -> jobs.fetchSourceJobQueueListWithJobId(7L, 500, null, "started", "desc", -1))
            .isInstanceOf(ListSort.Refused.class).hasMessage("offset is 0 or more.");
        assertThatThrownBy(() -> jobs.fetchSourceJobQueueListWithJobId(7L, 500, null, "message", "desc", 0))
            .isInstanceOf(ListSort.Refused.class).hasMessage(ALLOWED);
    }
}
