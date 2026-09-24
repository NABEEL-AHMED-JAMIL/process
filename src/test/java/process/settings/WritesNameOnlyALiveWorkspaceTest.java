package process.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.identity.IdentityPort;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-166, the WRITE-time control. With no foreign key onto tenant, what stops a row being filed under a
 * workspace that is not one is the code that decides tenant_id:
 * - a tenant user or admin never names one: whatever the request body says, their own (from the validated
 *   token, via TenantContext) is used;
 * - a platform administrator names one, and it must be a LIVE workspace -- one Identity deleted is refused
 *   exactly as one that never existed, since its tenant.deleted reaction has already stopped its jobs.
 */
class WritesNameOnlyALiveWorkspaceTest {

    private final IdentityPort identity = mock(IdentityPort.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Long resolved(Long requested) {
        Long[] tenant = new Long[1];
        String refusal = ActingWorkspace.resolve(this.identity, requested, id -> tenant[0] = id);
        return refusal == null ? tenant[0] : null;
    }

    @Test
    void aTenantCallerCannotNameAnotherWorkspace() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@a.example");

        assertThat(resolved(2902L)).as("the body's tenant is ignored").isEqualTo(2901L);
        assertThat(resolved(null)).isEqualTo(2901L);
        verify(this.identity, never()).workspace(any());
    }

    @Test
    void aTenantCallerWithNoWorkspaceIsRefused() {
        TenantContext.set(null, "TENANT_USER", 43L, "lost@a.example");
        assertThat(ActingWorkspace.resolve(this.identity, 2901L, id -> { })).isNotNull();
    }

    @Test
    void aPlatformAdministratorMayNameALiveWorkspaceOnly() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.identity.workspace(2901L)).thenReturn(Optional.of(new IdentityPort.Workspace(2901L, "A", "a", "Active")));
        when(this.identity.workspace(2902L)).thenReturn(Optional.of(new IdentityPort.Workspace(2902L, "B", "b", "Delete")));
        when(this.identity.workspace(2903L)).thenReturn(Optional.of(new IdentityPort.Workspace(2903L, "C", "c", "Suspended")));
        when(this.identity.workspace(2904L)).thenReturn(Optional.empty());

        assertThat(resolved(2901L)).isEqualTo(2901L);
        assertThat(resolved(2903L)).as("suspended is still a workspace: its admin can be restored").isEqualTo(2903L);
        assertThat(resolved(2902L)).as("deleted in Identity").isNull();
        assertThat(resolved(2904L)).as("never existed").isNull();
        assertThat(ActingWorkspace.resolve(this.identity, 2902L, id -> { }))
            .isEqualTo(ActingWorkspace.resolve(this.identity, 2904L, id -> { }).replace("2904", "2902"));
    }

    @Test
    void theLiveWorkspaceQuestionIsThePorts() {
        when(this.identity.workspace(5L)).thenReturn(Optional.of(new IdentityPort.Workspace(5L, "W", "w", "Delete")));
        when(this.identity.workspace(6L)).thenReturn(Optional.of(new IdentityPort.Workspace(6L, "W", "w", "Active")));
        assertThat(IdentityPort.live(this.identity, 5L)).isEmpty();
        assertThat(IdentityPort.live(this.identity, 6L)).isPresent();
        assertThat(IdentityPort.live(this.identity, null)).isEmpty();
        assertThat(IdentityPort.live(null, 6L)).isEmpty();
    }

    /**
     * Every Core write that lets a platform administrator name a workspace asks IdentityPort.live, not workspace:
     * a new one that asks the looser question fails here, rather than filing rows under a deleted workspace.
     */
    @Test
    void noWritePathAsksTheLooserQuestion() throws IOException {
        Pattern looser = Pattern.compile("identity\\.workspace\\(");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java/process"))) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/identity/"))
                .filter(p -> {
                    try {
                        return looser.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).find();
                    } catch (IOException unreadable) {
                        throw new IllegalStateException(unreadable);
                    }
                })
                .map(Path::toString).collect(Collectors.toList());
        }
        assertThat(offenders).isEmpty();
    }
}
