package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import org.barco.platform.api.Problem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.forms.FormWorkflows;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-332: POST /v1/events -- an event of the organisation's own type starts what the workspace's routes name; a type no
 * route takes, or data a route's contract refuses, is a 422 that starts nothing; the same event twice starts once.
 */
class CustomerEventsTest {

    static final long TENANT = 2946L;

    private final EventRouteStore routes = mock(EventRouteStore.class);
    private final PipelineCatalogue catalogue = mock(PipelineCatalogue.class);
    private final InputContracts contracts = mock(InputContracts.class);
    private final RunIntake intake = mock(RunIntake.class);
    private final FormWorkflows workflows = mock(FormWorkflows.class);
    private final CustomerEvents events = new CustomerEvents(this.routes, this.catalogue, this.contracts, this.intake, this.workflows,
        new Idempotency(new MemoryReceipts()));

    @BeforeEach
    void asAClient() {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_test");
        TenantContext.setApiClient("cl_test", new LinkedHashSet<>(Collections.singletonList("events:write")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static EventRouteStore.Route route(long id, String kind, Long jobId, String workflowKey, String contractName) {
        EventRouteStore.Route route = new EventRouteStore.Route();
        route.routeId = id;
        route.tenantId = TENANT;
        route.eventType = "order.received";
        route.targetKind = kind;
        route.jobId = jobId;
        route.workflowKey = workflowKey;
        route.contractName = contractName;
        route.status = "Active";
        return route;
    }

    @Test
    void anEventStartsEveryRoutesTargetOnceAndSaysWhatStarted() {
        List<EventRouteStore.Route> both = Arrays.asList(route(1, "PIPELINE", 42L, null, null), route(2, "WORKFLOW", null, "approve", null));
        when(this.routes.activeFor(TENANT, "order.received")).thenReturn(both);
        when(this.routes.recordEvent(TENANT, "order.received", "cl_test")).thenReturn(1001L);
        when(this.catalogue.find(TENANT, "42")).thenReturn(Optional.of(new PipelineCatalogue.Entry(42L, TENANT, "Orders", "Active", null,
            null, null)));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", "7001");
        when(this.intake.start(any(), any())).thenReturn(new RunIntake.Outcome(run, null));
        when(this.workflows.startFor(eq(TENANT), eq("approve"), eq("api-event:1001"), anyString(), anyString(), anyMap()))
            .thenReturn(new FormWorkflows.Started(55L, "Open"));

        CustomerAnswer answer = this.events.send(json("{\"type\":\"order.received\",\"data\":{\"order\":7}}"), "event-0001");
        CustomerAnswer again = this.events.send(json("{\"type\":\"order.received\",\"data\":{\"order\":7}}"), "event-0001");

        assertThat(answer.status).isEqualTo(202);
        assertThat(answer.body.get("eventId")).isEqualTo("1001");
        assertThat(answer.body.toString()).contains("started=[{id=7001}]").contains("workflowKey=approve").contains("notStarted=[]");
        assertThat(again.replayed).isTrue();
        assertThat(again.body).isEqualTo(answer.body);
        verify(this.intake, times(1)).start(any(), any());
        verify(this.workflows, times(1)).startFor(anyLong(), anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(this.routes, times(1)).recordEvent(anyLong(), anyString(), anyString());
    }

    @Test
    void aTypeNoRouteTakesIs422() {
        when(this.routes.activeFor(TENANT, "unknown.type")).thenReturn(Collections.<EventRouteStore.Route>emptyList());

        CustomerAnswer answer = this.events.send(json("{\"type\":\"unknown.type\",\"data\":{}}"), "event-0002");

        assertThat(answer.status).isEqualTo(422);
        assertThat(answer.body.toString()).contains("path=type");
        verify(this.routes, never()).recordEvent(anyLong(), anyString(), anyString());
    }

    @Test
    void dataTheContractRefusesIs422UnderDataAndStartsNothing() {
        when(this.routes.activeFor(TENANT, "order.received")).thenReturn(Collections.singletonList(route(1, "PIPELINE", 42L, null, "orders")));
        InputContracts.Checked refused = new InputContracts.Checked();
        refused.valid = false;
        refused.errors = Collections.singletonList(new Problem.FieldError("data.order", "must be a number"));
        when(this.contracts.check(eq(TENANT), any(), any(JsonNode.class), eq("data"))).thenReturn(refused);

        CustomerAnswer answer = this.events.send(json("{\"type\":\"order.received\",\"data\":{\"order\":\"seven\"}}"), "event-0003");

        assertThat(answer.status).isEqualTo(422);
        assertThat(answer.body.toString()).contains("path=data.order").doesNotContain("seven");
        verify(this.routes, never()).recordEvent(anyLong(), anyString(), anyString());
        verify(this.intake, never()).start(any(), any());
    }

    @Test
    void aMisshapenEventIsRefused() {
        assertThat(this.events.send(json("{\"type\":\"has spaces\",\"data\":[]}"), "event-0004").body.toString())
            .contains("path=type").contains("path=data");
        assertThat(this.events.send(json("nope"), "event-0005").status).isEqualTo(400);
        assertThat(this.events.send(json("{}"), null).status).isEqualTo(400);
    }

    @Test
    void theScopeIsEventsWrite() {
        TenantContext.setApiClient("cl_test", new LinkedHashSet<>(Collections.singletonList("runs:write")));
        assertThat(this.events.send(json("{}"), "event-0006").status).isEqualTo(403);
    }
}
