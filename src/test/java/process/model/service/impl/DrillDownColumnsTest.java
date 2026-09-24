package process.model.service.impl;

import process.util.BusinessTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.MessageQSearchDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-8 (DEF-126), the half the V50 baseline left: the Dashboard drill-down read job_queue with
 * select * and mapped the Object[] by position, expecting job_queue_id and then the alphabetical order
 * of the columns that existed when it was written. The live table's order is not that -- attempt,
 * bucket and the callback-token columns were added later -- and a table provisioned anywhere new by
 * ddl-auto puts attempt at index 1, where Timestamp.valueOf("1") throws and the endpoint 500s. Nothing
 * pointed at column order. The query names its columns now, in the order DashboardServiceImpl reads.
 */
class DrillDownColumnsTest {

    /** The order DashboardServiceImpl and MessageQServiceImpl read, index by index. */
    static final List<String> RUN_COLUMNS = Collections.unmodifiableList(Arrays.asList("job_queue_id", "date_created",
        "end_time", "job_id", "job_send", "job_status", "job_status_message", "run_manual", "skip_manual", "skip_time",
        "start_time"));

    private final QueryService queries = new QueryService();

    @BeforeEach
    void platformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theDrillDownNamesEveryColumnItReadsInTheOrderItReadsThem() {
        String sql = this.queries.weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", 7L);

        assertThat(selectList(sql)).containsExactlyElementsOf(qualified("job_queue", RUN_COLUMNS));
    }

    @Test
    void theRunLogListReadsTheSameColumnsInTheSameOrder() {
        MessageQSearchDto search = new MessageQSearchDto();
        search.setFromDate("2026-09-01");
        search.setToDate("2026-09-21");

        assertThat(selectList(this.queries.fetchJobQLog(search, false))).containsExactlyElementsOf(qualified("jq", RUN_COLUMNS));
    }

    @Test
    void noQueryInTheClassSelectsStar() {
        List<String> built = Arrays.asList(
            this.queries.weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, null, null),
            this.queries.weeklyHrRunningStatisticsDimension("2026-09-21", 14L),
            this.queries.weeklyHrsRunningJobStatistics("2026-09-15", "2026-09-21"),
            this.queries.weeklyRunningJobStatistics("2026-09-15", "2026-09-21"),
            this.queries.jobStatusStatistics("2026-09-15", "2026-09-21"),
            this.queries.jobRunningStatistics("2026-09-15", "2026-09-21"),
            this.queries.userStatistics("2026-09-15", "2026-09-21"),
            this.queries.runReportRows("2026-09-15", "2026-09-21"),
            this.queries.statisticsBySourceJobId(7L),
            this.queries.listSourceTaskQuery(false, null, null, null, null, null),
            this.queries.fetchAllLinkJobsWithSourceTaskQuery(false, 7L, null, null, null));
        for (String sql : built) {
            assertThat(sql).as(sql).doesNotContainPattern("(?i)select\\s+(\\w+\\.)?\\*");
        }
    }

    /**
     * The values, not just the shape: a positional mis-map still returns a well-formed object, so the
     * row here is the drill-down's own column order and every field is checked against its column.
     */
    @Test
    void aRowInThatOrderRendersEveryField() throws Exception {
        QueryService rows = mock(QueryService.class);
        when(rows.weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", null)).thenReturn("drill-down");
        when(rows.executeQuery(anyString())).thenReturn(new ArrayList<>(Collections.singletonList(new Object[] {
            // What the driver hands back for a timestamptz column: the instant (here, Chicago afternoon ones).
            5073L, chicago("2026-09-21T14:03:07"), chicago("2026-09-21T14:05:09"), 1196L, true, "Completed",
            "done", false, false, null, chicago("2026-09-21T14:04:01")})));
        DashboardServiceImpl dashboard = new DashboardServiceImpl(rows, null, null, null, null);

        ResponseDto response = dashboard.weeklyHrRunningStatisticsDimensionDetail("2026-09-21", 14L, "Completed", null);

        @SuppressWarnings("unchecked")
        List<SourceJobQueueDto> runs = (List<SourceJobQueueDto>) ((Map<String, Object>) response.getData()).get("sourceJobQueues");
        SourceJobQueueDto run = runs.get(0);
        assertThat(run.getJobQueueId()).isEqualTo(5073L);
        assertThat(run.getDateCreated()).isEqualTo(chicago("2026-09-21T14:03:07"));
        assertThat(run.getEndTime()).isEqualTo(LocalDateTime.of(2026, 9, 21, 14, 5, 9));
        assertThat(run.getJobId()).isEqualTo(1196L);
        assertThat(run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(run.getJobStatusMessage()).isEqualTo("done");
        assertThat(run.getRunManual()).isFalse();
        assertThat(run.getSkipTime()).isNull();
        assertThat(run.getStartTime()).isEqualTo(LocalDateTime.of(2026, 9, 21, 14, 4, 1));
    }

    static List<String> selectList(String sql) {
        Matcher select = Pattern.compile("(?is)^\\s*select\\s+(.*?)\\s+from\\s").matcher(sql);
        assertThat(select.find()).as(sql).isTrue();
        List<String> columns = new ArrayList<>();
        for (String column : select.group(1).split(",")) {
            columns.add(column.trim());
        }
        return columns;
    }

    private static List<String> qualified(String alias, List<String> columns) {
        List<String> names = new ArrayList<>();
        for (String column : columns) {
            names.add(alias + "." + column);
        }
        return names;
    }

    private static Timestamp chicago(String wallClock) {
        return BusinessTime.timestampOf(LocalDateTime.parse(wallClock));
    }
}
