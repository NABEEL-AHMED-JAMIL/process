package process.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.pojo.TaskReference;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-167: home pages and task groups as their own typed rows. A name is unique per workspace and kind (D3), a home
 * page is an http(s) URL, a row in use by a live task cannot be deleted (MIG-165's rule, carried across), and another
 * workspace's row answers exactly like one that does not exist.
 */
class TaskReferenceServiceTest {

    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;

    private final TaskReferenceRepository references = mock(TaskReferenceRepository.class);
    private final SourceTaskRepository tasks = mock(SourceTaskRepository.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private TaskReferenceService service;

    @BeforeEach
    void setUp() {
        this.service = new TaskReferenceService(this.references, this.tasks, this.identity, this.names);
        TenantContext.set(MINE, "TENANT_ADMIN", 42L, "ops@medaxis.test");
        when(this.names.namesFor(any())).thenReturn(Collections.emptyMap());
        when(this.references.save(any(TaskReference.class))).thenAnswer(call -> {
            TaskReference saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(1300L);
            }
            if (saved.getCreatedAt() == null) {
                saved.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            }
            return saved;
        });
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aHomePageIsAddedToTheCallersWorkspace() {
        ResponseDto added = this.service.add(request(null, "HOME_PAGE", "Ops home", "https://ops.medaxis.demo/done", THEIRS));

        assertThat(added.getStatus()).as(added.getMessage()).isEqualTo("SUCCESS");
        TaskReference saved = this.saved();
        assertThat(saved.getTenantId()).as("a tenant admin's tenantId is ignored").isEqualTo(MINE);
        assertThat(saved.getKind()).isEqualTo("HOME_PAGE");
        assertThat(((TaskReferenceDto) added.getData()).getId()).isEqualTo(1300L);
    }

    @Test
    void aHomePageIsAnHttpUrlAndAGroupsLabelIsOptional() {
        assertThat(this.service.add(request(null, "HOME_PAGE", "Ops", "ops.medaxis.demo", (Long) null)).getMessage()).contains("http");
        assertThat(this.service.add(request(null, "HOME_PAGE", "Ops", "javascript:alert(1)", null)).getMessage()).contains("http");
        assertThat(this.service.add(request(null, "HOME_PAGE", "Ops", "", (Long) null)).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.add(request(null, "TASK_GROUP", " ", null, (Long) null)).getMessage()).contains("name");
        assertThat(this.service.add(request(null, "BUCKET", "x", null, (Long) null)).getMessage()).contains("HOME_PAGE or TASK_GROUP");
        verify(this.references, never()).save(any());

        assertThat(this.service.add(request(null, "TASK_GROUP", "Nightly", null, (Long) null)).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aNameIsUniqueInItsWorkspaceAndKind() {
        when(this.references.findByTenantIdAndKindAndName(MINE, "TASK_GROUP", "Nightly")).thenReturn(Optional.of(row(1280L, MINE, "TASK_GROUP", "Nightly")));

        assertThat(this.service.add(request(null, "TASK_GROUP", "Nightly", null, (Long) null)).getMessage())
            .isEqualTo("A task group named Nightly already exists in this workspace.");

        when(this.references.findById(1281L)).thenReturn(Optional.of(row(1281L, MINE, "TASK_GROUP", "Weekly")));
        assertThat(this.service.update(request(1281L, null, "Nightly", null, (Long) null)).getStatus()).isEqualTo("ERROR");
        verify(this.references, never()).save(any());
    }

    @Test
    void renamingARowToItsOwnNameIsNotADuplicate() {
        TaskReference nightly = row(1280L, MINE, "TASK_GROUP", "Nightly");
        when(this.references.findById(1280L)).thenReturn(Optional.of(nightly));
        when(this.references.findByTenantIdAndKindAndName(MINE, "TASK_GROUP", "Nightly")).thenReturn(Optional.of(nightly));

        ResponseDto updated = this.service.update(described(1280L, null, "Nightly", "", "runs at 2am"));

        assertThat(updated.getStatus()).as(updated.getMessage()).isEqualTo("SUCCESS");
        assertThat(this.saved().getValue()).as("a blank label clears it").isNull();
        assertThat(this.saved().getDescription()).isEqualTo("runs at 2am");
    }

    @Test
    void anotherWorkspacesRowIsNotFound() {
        when(this.references.findById(1274L)).thenReturn(Optional.of(row(1274L, THEIRS, "HOME_PAGE", "CareBridge Home")));

        assertThat(this.service.update(request(1274L, null, "Mine now", "https://x.demo", (Long) null)).getMessage()).isEqualTo("Entry 1274 not found.");
        assertThat(this.service.delete(1274L).getMessage()).isEqualTo("Entry 1274 not found.");
        assertThat(this.service.delete(99L).getMessage()).isEqualTo("Entry 99 not found.");
        verify(this.references, never()).save(any());
        verify(this.references, never()).delete(any());
    }

    @Test
    void aRowALiveTaskUsesCannotBeDeleted() {
        when(this.references.findById(1275L)).thenReturn(Optional.of(row(1275L, MINE, "HOME_PAGE", "MedAxis Home")));
        when(this.tasks.countLiveTasksReferencing(1275L)).thenReturn(1L);

        assertThat(this.service.delete(1275L).getMessage()).isEqualTo("1 task still uses \"MedAxis Home\". Change that task first.");
        verify(this.references, never()).delete(any());

        when(this.tasks.countLiveTasksReferencing(1275L)).thenReturn(0L);
        assertThat(this.service.delete(1275L).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void theKindOfARowCannotChange() {
        when(this.references.findById(1280L)).thenReturn(Optional.of(row(1280L, MINE, "TASK_GROUP", "Nightly")));
        TaskReferenceDto request = request(1280L, "HOME_PAGE", "Nightly", "https://x.demo", (Long) null);

        assertThat(this.service.update(request).getMessage()).contains("cannot change");
    }

    @Test
    void aTenantAdminListsOnlyTheirOwnAndAPlatformAdminMayFilter() {
        this.service.list("HOME_PAGE", THEIRS);
        verify(this.references).findByTenantIdAndKindOrderByNameAsc(MINE, "HOME_PAGE");

        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        this.service.list("TASK_GROUP", THEIRS);
        verify(this.references).findByTenantIdAndKindOrderByNameAsc(THEIRS, "TASK_GROUP");
        this.service.list("TASK_GROUP", null);
        verify(this.references).findByKindOrderByTenantIdAscNameAsc("TASK_GROUP");
        assertThat(this.service.list("NOPE", null).getStatus()).isEqualTo("ERROR");
    }

    private TaskReference saved() {
        ArgumentCaptor<TaskReference> captor = ArgumentCaptor.forClass(TaskReference.class);
        verify(this.references, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    static TaskReferenceDto request(Long id, String kind, String name, String value, Long tenantId) {
        TaskReferenceDto dto = new TaskReferenceDto();
        dto.setId(id);
        dto.setKind(kind);
        dto.setName(name);
        dto.setValue(value);
        dto.setTenantId(tenantId);
        return dto;
    }

    private static TaskReferenceDto described(Long id, String kind, String name, String value, String description) {
        TaskReferenceDto dto = request(id, kind, name, value, (Long) null);
        dto.setDescription(description);
        return dto;
    }

    static TaskReference row(Long id, Long tenant, String kind, String name) {
        TaskReference row = new TaskReference();
        row.setId(id);
        row.setTenantId(tenant);
        row.setKind(kind);
        row.setName(name);
        row.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        return row;
    }
}
