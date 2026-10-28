package process.monitoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.security.TenantContext;
import process.util.ListSort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner 2026-10-27 ("add a sort on each table"): Runs › Failed sorts its rows by Run, Pipeline, Kind, Ended -- and Workspace
 * for a platform administrator -- before the page is cut, empty values last both ways, ties by the run. Anything else is a
 * 400 naming the columns, refused before the database is asked.
 */
class FailedRunsSortTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static FailedRuns.Row row(long id, long tenant, String pipeline, String kind, Instant ended) {
        FailedRuns.Row r = new FailedRuns.Row();
        r.runId = id;
        r.tenantId = tenant;
        r.taskName = pipeline;
        r.kind = kind;
        r.ended = ended;
        return r;
    }

    private static List<FailedRuns.Row> rows() {
        return Arrays.asList(
            row(1, 10, "Claims intake", ErrorSignatures.DATA, T.plusSeconds(30)),
            row(2, 20, null, ErrorSignatures.TIMED_OUT, null),
            row(3, 10, "audit export", ErrorSignatures.SERVICE, T.plusSeconds(10)),
            row(4, 20, "Claims intake", ErrorSignatures.DATA, T.plusSeconds(30)),
            row(5, 30, "Wound photos", ErrorSignatures.OTHER, T.plusSeconds(20)));
    }

    private static List<Long> sorted(String column, String dir) {
        Map<Long, String> names = new HashMap<>();
        names.put(10L, "Riverside (synthetic)");
        names.put(20L, "meridian (synthetic)");
        List<FailedRuns.Row> list = new ArrayList<>(rows());
        list.sort(FailedRuns.order(ListSort.parse(column, dir, FailedRuns.sorts(true)), names));
        return list.stream().map(r -> r.runId).collect(Collectors.toList());
    }

    @Test
    void everyColumnSortsBothWaysWithEmptyValuesLastAndTiesByTheRun() {
        assertThat(sorted("run", "asc")).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(sorted("run", "desc")).containsExactly(5L, 4L, 3L, 2L, 1L);
        // Ignoring case; run 2 has no pipeline, last either way; runs 1 and 4 tie, by the run in the sort's direction.
        assertThat(sorted("pipeline", "asc")).containsExactly(3L, 1L, 4L, 5L, 2L);
        assertThat(sorted("pipeline", "desc")).containsExactly(5L, 4L, 1L, 3L, 2L);
        // By the label the console shows: "A service it called", "Other", "The data", "Timed out or stopped responding".
        assertThat(sorted("kind", "asc")).containsExactly(3L, 5L, 1L, 4L, 2L);
        assertThat(sorted("kind", "desc")).containsExactly(2L, 4L, 1L, 5L, 3L);
        // Run 2 never ended: last both ways.
        assertThat(sorted("ended", "asc")).containsExactly(3L, 5L, 1L, 4L, 2L);
        assertThat(sorted("ended", "desc")).containsExactly(4L, 1L, 5L, 3L, 2L);
        // Workspace 30 has no name (identity did not answer for it): last both ways.
        assertThat(sorted("workspace", "asc")).containsExactly(2L, 4L, 1L, 3L, 5L);
        assertThat(sorted("workspace", "desc")).containsExactly(3L, 1L, 4L, 2L, 5L);
    }

    @Test
    void workspaceIsAPlatformAdministratorsColumnOnly() {
        assertThat(FailedRuns.sorts(false)).containsExactly("run", "pipeline", "kind", "ended");
        assertThat(FailedRuns.sorts(true)).containsExactly("run", "pipeline", "kind", "ended", "workspace");
    }

    @Test
    void anUnknownColumnOrDirectionIsRefusedBeforeTheDatabaseIsAsked() {
        // No JdbcTemplate: the refusal comes first.
        FailedRuns failed = new FailedRuns(null, null);
        FailedRuns.Query q = new FailedRuns.Query();
        q.sort = "workspace";
        assertThatThrownBy(() -> failed.search(q, false)).isInstanceOf(AlertRules.Refused.class)
            .hasMessage("sort must be one of: run, pipeline, kind, ended.")
            .satisfies(e -> assertThat(((AlertRules.Refused) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        q.sort = "ended";
        q.dir = "newest";
        assertThatThrownBy(() -> failed.search(q, true)).isInstanceOf(AlertRules.Refused.class).hasMessage("dir must be asc or desc.");
    }

    @Test
    void overHttpTheRefusalIsA400NamingTheColumns() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailedRunsRestApi(new FailedRuns(null, null), null)).build();
        TenantContext.set(8861L, "TENANT_ADMIN", 9861L, "admin@riverside.test");
        mvc.perform(post("/message.json/failedRuns").contentType("application/json").content("{\"sort\": \"error\", \"dir\": \"asc\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value("sort must be one of: run, pipeline, kind, ended."));
    }
}
