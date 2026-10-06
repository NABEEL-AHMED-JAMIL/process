package process.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.security.TenantContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-296: a platform administrator's job, task and drill-down lists mix every workspace, and said
 * nowhere which row was whose. Names come from Identity (the source of truth; Core's own tenant table
 * is years stale), in one call per list, and only for the one caller who sees more than one workspace.
 */
class WorkspaceNamesTest {

    private final IdentityPort identity = mock(IdentityPort.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aPlatformAdminGetsEachWorkspacesNameInOneCall() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        when(this.identity.workspaces(anyCollection())).thenReturn(List.of(
            new IdentityPort.Workspace(2924L, "Claude Demo", "DEMO", "Active"),
            new IdentityPort.Workspace(2900L, "Default", "DEF", "Active")));

        Map<Long, String> names = WorkspaceNames.forCaller(this.identity, Arrays.asList(2924L, 2900L, 2924L, null));

        assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.of(2924L, "Claude Demo", 2900L, "Default"));
    }

    @Test
    void anyoneElseSeesOneWorkspaceAndIsNotAsked() {
        TenantContext.set(2924L, "TENANT_ADMIN", 4537L, "admin@demo.local");

        assertThat(WorkspaceNames.forCaller(this.identity, List.of(2924L))).isEmpty();
        verify(this.identity, never()).workspaces(anyCollection());
    }

    /** The names are a courtesy: when Identity cannot be asked, the list still loads without them. */
    @Test
    void theListStillLoadsWhenIdentityCannotBeAsked() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        when(this.identity.workspaces(anyCollection())).thenThrow(new IdentityPort.Unavailable("identity is down", null));

        assertThat(WorkspaceNames.forCaller(this.identity, List.of(2924L))).isEmpty();
        assertThat(WorkspaceNames.forCaller(null, List.of(2924L))).isEmpty();
    }
}
