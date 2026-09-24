package process.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.config.GlobalExceptionHandler;
import process.model.service.DashboardService;
import process.model.dto.ResponseDto;
import process.model.service.impl.QueryService;
import process.model.service.impl.ReportExportServiceImpl;
import process.util.RequestRefused;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MIG-103 (B6, DEF-136): the Dashboard answered HTTP 500 for everything, so a malformed date and a
 * dead connection pool were byte-identical and both paged on-call. Now a request the Dashboard
 * refuses is a 200 carrying ERROR and the sentence -- the platform's contract (ADR-023) -- and only
 * an infrastructure fault is a 500. An observable contract change: the console shows the sentence.
 */
class DashboardRefusalTest {

    private final DashboardService dashboard = mock(DashboardService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.standaloneSetup(new DashboardRestApi(this.dashboard))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void aMalformedDateIsA200CarryingErrorAndTheSentence() throws Exception {
        when(this.dashboard.weeklyHrRunningStatisticsDimension(any(), any()))
            .thenThrow(new RequestRefused("Invalid date -- expected yyyy-MM-dd."));

        this.mvc.perform(get("/dashboard.json/weeklyHrRunningStatisticsDimension").param("targetDate", "2026-13-45").param("targetHr", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value("Invalid date -- expected yyyy-MM-dd."));
    }

    @Test
    void aDeadConnectionPoolIsStillA500AndLooksNothingLikeARefusal() throws Exception {
        when(this.dashboard.jobStatusStatistics(any(), any()))
            .thenThrow(new DataAccessResourceFailureException("Connection is not available, request timed out after 30000ms."));

        this.mvc.perform(get("/dashboard.json/jobStatusStatistics").param("startDate", "2026-09-01").param("endDate", "2026-09-24"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value(not(containsString("Connection"))));
    }

    /** The validators say "refused", not "broken" -- and stay catchable as the IllegalArgumentException they were. */
    @Test
    void theDashboardsValidatorsRefuse() {
        QueryService queries = new QueryService();
        assertThatThrownBy(() -> queries.weeklyHrRunningStatisticsDimension("2026-13-45", 3L)).isInstanceOf(RequestRefused.class)
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> queries.jobStatusStatistics("2026-02-30", "2026-03-01")).as("the shape of a date, not a date")
            .isInstanceOf(RequestRefused.class);
        assertThat(queries.jobStatusStatistics(null, null)).as("no dates is all time").doesNotContain("between");
        assertThat(queries.jobStatusStatistics("2026-09-01", "2026-09-24")).contains("between '2026-09-01' and '2026-09-24'");
        assertThatThrownBy(() -> queries.weeklyHrRunningStatisticsDimensionDetail("2026-09-24", 3L, "Nonsense", 1L))
            .isInstanceOf(RequestRefused.class).hasMessageContaining("jobStatus");
    }

    /** /report.json/runs already answered 200 + ERROR; a refusal now carries its own sentence, not "could not read". */
    @Test
    void theRunReportSaysWhyADateWasRefused() {
        ReportExportServiceImpl report =
            new ReportExportServiceImpl(null, null, new QueryService(), null);
        ResponseDto answer = report.runRows("2026-13-45", "2026-09-24");
        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).isEqualTo("Invalid date -- expected yyyy-MM-dd.");
    }
}
