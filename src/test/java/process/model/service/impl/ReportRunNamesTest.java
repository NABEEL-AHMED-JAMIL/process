package process.model.service.impl;

import org.junit.jupiter.api.Test;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-107: the run report names each run's owner and workspace from Identity, once for the whole page,
 * with the words the join used to give: the owner's full name, else their username, else "Unassigned";
 * the workspace's name, else "(no workspace)".
 */
class ReportRunNamesTest {

    private final QueryService queries = mock(QueryService.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final ReportExportServiceImpl reports = new ReportExportServiceImpl(null, null, this.queries, this.identity);

    /** A report row in runReportRows' column order: task, status, owner id, day, seconds, job, run, tenant id, exec seconds. */
    private static Object[] run(long runId, Long ownerId, Long tenantId) {
        return new Object[] {"Nightly claims", "Completed", ownerId, "2026-09-21", 41, "Job", runId, tenantId, 0.23};
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> page(Object[]... rows) throws Exception {
        when(this.queries.runReportRows(any(), any())).thenReturn("report");
        when(this.queries.executeQuery(anyString())).thenReturn(new ArrayList<>(Arrays.asList(rows)));
        return (Map<String, Object>) this.reports.runRows("2026-09-21", "2026-09-21").getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> named(Map<String, Object> page, String dimension, int column) {
        List<String> names = (List<String>) page.get(dimension);
        List<Object> out = new ArrayList<>();
        for (List<Object> row : (List<List<Object>>) page.get("rows")) {
            out.add(names.get((Integer) row.get(column)));
        }
        return out;
    }

    @Test
    void ownersAndWorkspacesAreNamedAsTheJoinNamedThem() throws Exception {
        Map<Long, IdentityPort.Person> people = new HashMap<>();
        people.put(10L, new IdentityPort.Person(10L, 2901L, "olivia@a.example", "Olivia Owen", "TENANT_USER", "Active"));
        people.put(11L, new IdentityPort.Person(11L, 2901L, "nameless@a.example", null, "TENANT_USER", "Active"));
        when(this.identity.people(any())).thenReturn(people);
        when(this.identity.workspaces(any())).thenReturn(Collections.singletonList(
            new IdentityPort.Workspace(2901L, "CareBridge", "CHS", "Active")));

        Map<String, Object> page = this.page(run(1, 10L, 2901L), run(2, 11L, 2901L), run(3, 12L, 2999L), run(4, null, null));

        assertThat(named(page, "owner", 2)).containsExactly("Olivia Owen", "nameless@a.example", "Unassigned", "Unassigned");
        assertThat(named(page, "tenant", 7)).containsExactly("CareBridge", "CareBridge", "(no workspace)", "(no workspace)");
    }

    /** Identity out of reach: the report says it could not be read, rather than calling every owner "Unassigned". */
    @Test
    void identityDownIsAReportThatCouldNotBeRead() throws Exception {
        when(this.identity.people(any())).thenThrow(new IdentityPort.Unavailable("identity down", null));
        when(this.queries.runReportRows(any(), any())).thenReturn("report");
        when(this.queries.executeQuery(anyString())).thenReturn(new ArrayList<>(Collections.singletonList(run(1, 10L, 2901L))));

        ResponseDto answer = this.reports.runRows("2026-09-21", "2026-09-21");

        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).isEqualTo("Could not read the runs for that range.");
    }
}
