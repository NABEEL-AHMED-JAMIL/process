package process.customer;

import org.barco.platform.tenancy.RowSecurity;
import org.barco.platform.tenancy.RowSecurityCaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import process.forms.FormWorkflows;
import process.model.dto.ResponseDto;
import process.security.TenantContext;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which workspace the console's event routes act on: a platform administrator's, the one he names, run as that workspace
 * alone; a workspace administrator's, always their own, whatever tenantId they send.
 */
class EventRoutesTest {

    private static final long RIVERSIDE = 2960L;
    private static final long OTHER = 2945L;
    private static final long JOB = 41L;

    /** The thread's row-security settings for an all-workspaces caller: a forTenant narrows them, nothing else does. */
    private static final RowSecurityCaller PLATFORM = new RowSecurityCaller() {
        @Override
        public Long tenantId() {
            return null;
        }

        @Override
        public boolean isPlatformAdmin() {
            return true;
        }
    };

    private final EventRouteStore store = mock(EventRouteStore.class);
    private final JdbcTemplate sql = mock(JdbcTemplate.class);
    private final FormWorkflows workflows = mock(FormWorkflows.class);
    private final InputContracts contracts = mock(InputContracts.class);
    private final EventRoutes routes = new EventRoutes(this.store, this.sql, this.workflows, this.contracts);
    private final List<String> scopes = new ArrayList<>();

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private void asPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 4639L, "qa-platform-admin");
    }

    private void asAdminOf(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 4640L, "admin@riverside.example");
    }

    /** The workspace's pipelines: one job, and the row-security scope each read ran under. */
    private void pipelinesOf(long tenantId) {
        doAnswer(asked -> {
            this.scopes.add(RowSecurity.current(PLATFORM).toString());
            if (Long.valueOf(tenantId).equals(asked.getArgument(2))) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong(1)).thenReturn(JOB);
                when(rs.getString(2)).thenReturn("Intake");
                ((RowCallbackHandler) asked.getArgument(1)).processRow(rs);
            }
            return null;
        }).when(this.sql).query(anyString(), any(RowCallbackHandler.class), anyLong());
    }

    private static EventRouteStore.Route route(long tenantId, long routeId) {
        EventRouteStore.Route route = new EventRouteStore.Route();
        route.tenantId = tenantId;
        route.routeId = routeId;
        route.eventType = "order.received";
        route.targetKind = EventRoutes.PIPELINE;
        route.jobId = JOB;
        route.status = "Active";
        return route;
    }

    private static EventRouteRequest saveOf(Long tenantId) {
        EventRouteRequest request = new EventRouteRequest();
        request.setTenantId(tenantId);
        request.setEventType("order.received");
        request.setTargetKind("PIPELINE");
        request.setJobId(JOB);
        return request;
    }

    @Test
    void aPlatformAdministratorWhoNamesNoWorkspaceIsAskedToAndNothingIsRead() {
        asPlatformAdmin();

        for (ResponseDto answer : new ResponseDto[] {this.routes.list(null), this.routes.targets(null), this.routes.save(saveOf(null)),
            this.routes.list(0L)}) {
            assertThat(answer.getStatus()).isEqualTo("ERROR");
            assertThat(answer.getMessage()).startsWith("Name the workspace");
        }
        EventRouteRequest delete = new EventRouteRequest();
        delete.setRouteId(7L);
        assertThat(this.routes.delete(delete).getMessage()).startsWith("Name the workspace");
        verify(this.store, never()).list(anyLong());
        verify(this.store, never()).insert(any());
        verify(this.store, never()).delete(anyLong(), anyLong());
    }

    @Test
    void aPlatformAdministratorReadsTheNamedWorkspaceAsThatWorkspaceAlone() {
        asPlatformAdmin();
        pipelinesOf(RIVERSIDE);
        doAnswer(asked -> {
            this.scopes.add(RowSecurity.current(PLATFORM).toString());
            return Collections.singletonList(route(RIVERSIDE, 3L));
        }).when(this.store).list(RIVERSIDE);

        ResponseDto list = this.routes.list(RIVERSIDE);
        ResponseDto targets = this.routes.targets(RIVERSIDE);

        assertThat(list.getStatus()).isEqualTo("SUCCESS");
        assertThat(String.valueOf(list.getData())).contains("order.received").contains("pipelineName=Intake");
        assertThat(String.valueOf(targets.getData())).contains("jobId=" + JOB);
        assertThat(this.scopes).isNotEmpty().allMatch(("tenant " + RIVERSIDE)::equals);
        assertThat(RowSecurity.current(PLATFORM).toString()).as("the grant is back afterwards").isEqualTo("all tenants");
    }

    @Test
    void aPlatformAdministratorSavesAndRemovesInTheNamedWorkspace() {
        asPlatformAdmin();
        pipelinesOf(RIVERSIDE);
        when(this.store.list(RIVERSIDE)).thenReturn(Collections.<EventRouteStore.Route>emptyList());
        when(this.store.insert(any())).thenReturn(9L);
        when(this.store.find(RIVERSIDE, 9L)).thenReturn(Optional.of(route(RIVERSIDE, 9L)));
        when(this.store.delete(RIVERSIDE, 9L)).thenReturn(true);

        ResponseDto saved = this.routes.save(saveOf(RIVERSIDE));
        EventRouteRequest delete = new EventRouteRequest();
        delete.setTenantId(RIVERSIDE);
        delete.setRouteId(9L);
        ResponseDto removed = this.routes.delete(delete);

        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<EventRouteStore.Route> written = ArgumentCaptor.forClass(EventRouteStore.Route.class);
        verify(this.store).insert(written.capture());
        assertThat(written.getValue().tenantId).isEqualTo(RIVERSIDE);
        assertThat(written.getValue().createdBy).isEqualTo(4639L);
        assertThat(removed.getStatus()).isEqualTo("SUCCESS");
        verify(this.store).delete(RIVERSIDE, 9L);
    }

    @Test
    void aWorkspaceAdministratorsTenantIdIsIgnored() {
        asAdminOf(RIVERSIDE);
        pipelinesOf(RIVERSIDE);
        when(this.store.list(RIVERSIDE)).thenReturn(Collections.singletonList(route(RIVERSIDE, 3L)));
        when(this.store.insert(any())).thenReturn(9L);
        when(this.store.find(RIVERSIDE, 9L)).thenReturn(Optional.of(route(RIVERSIDE, 9L)));

        assertThat(this.routes.list(OTHER).getStatus()).isEqualTo("SUCCESS");
        this.routes.targets(OTHER);
        this.routes.save(saveOf(OTHER));
        EventRouteRequest delete = new EventRouteRequest();
        delete.setTenantId(OTHER);
        delete.setRouteId(3L);
        this.routes.delete(delete);

        verify(this.store, never()).list(OTHER);
        verify(this.sql, never()).query(anyString(), any(RowCallbackHandler.class), eq(OTHER));
        ArgumentCaptor<EventRouteStore.Route> written = ArgumentCaptor.forClass(EventRouteStore.Route.class);
        verify(this.store).insert(written.capture());
        assertThat(written.getValue().tenantId).isEqualTo(RIVERSIDE);
        verify(this.store).delete(RIVERSIDE, 3L);
        verify(this.store, never()).delete(eq(OTHER), anyLong());
        assertThat(this.scopes).isNotEmpty().allMatch(("tenant " + RIVERSIDE)::equals);
    }

    @Test
    void aCallerWithNoWorkspaceIsRefused() {
        TenantContext.set(null, "TENANT_ADMIN", 1L, "nobody");

        assertThat(this.routes.list(RIVERSIDE).getMessage()).isEqualTo("Event routes belong to a workspace.");
        assertThat(this.routes.save(saveOf(RIVERSIDE)).getMessage()).isEqualTo("Event routes belong to a workspace.");
        verify(this.store, never()).list(anyLong());
        verify(this.store, never()).insert(any());
    }
}
