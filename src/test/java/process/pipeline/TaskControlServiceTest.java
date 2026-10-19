package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.repository.PipelineRepository;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner #16: a platform administrator enables, disables or blocks a Task Registry task per workspace -- the tools' model.
 * A block is refused at design and save time naming the task, the workspace's administrator cannot switch it on, Enabled
 * or Disabled lifts it, Default puts the task back; every change (the platform's and the workspace's) is in the history,
 * newest first, with who and as which role. legacy is never controlled, and only a platform administrator may control.
 */
class TaskControlServiceTest {

    private static final long TENANT = 41L;
    private static final long OTHER = 42L;
    private static final long KEY = 9101L;
    private static final long ADMIN = 7L;
    private static final long PLATFORM = 9001L;

    private final PipelineRepository pipelines = mock(PipelineRepository.class);
    private final PipelineDefinitionStore store = mock(PipelineDefinitionStore.class);
    private final StepTasks tasks = Definitions.builtInTasks();
    private final InMemoryTaskOverrideStore overrides = new InMemoryTaskOverrideStore();
    private final TaskRegistry registry = new TaskRegistry(this.tasks, this.overrides);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final PipelineDefinitionService definitions = new PipelineDefinitionService(this.pipelines, this.store,
        new DefinitionValidator(this.registry), this.registry, this.overrides, this.names);
    private final TaskControlService controls = new TaskControlService(this.registry, this.identity, this.names);

    private static final String USES_SAMPLE = "version: 1\nsteps:\n  - {key: read, task: sample, config: {rows: [{id: 1}]}}\n";

    @BeforeEach
    void setUp() {
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineKey(KEY);
        pipeline.setTenantId(TENANT);
        pipeline.setPipelineId("F768927");
        when(this.pipelines.findByPipelineKeyAndStatusNot(KEY, Status.Delete)).thenReturn(Optional.of(pipeline));
        when(this.pipelines.findAllByTenantIdAndStatusNotOrderByPipelineKeyDesc(anyLong(), any())).thenReturn(Collections.emptyList());
        when(this.store.latest(KEY)).thenReturn(Optional.empty());
        when(this.store.versions(KEY)).thenReturn(Collections.emptyList());
        when(this.identity.workspace(TENANT)).thenReturn(Optional.of(new IdentityPort.Workspace(TENANT, "Riverside", "riverside", "Active")));
        when(this.identity.workspace(OTHER)).thenReturn(Optional.of(new IdentityPort.Workspace(OTHER, "Meridian", "meridian", "Active")));
        Map<Long, String> people = new HashMap<>();
        people.put(ADMIN, "Riverside admin");
        people.put(PLATFORM, "QA platform admin");
        when(this.names.namesFor(any())).thenReturn(people);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private static TaskControlService.PlatformControl control(String code, String state, String reason) {
        TaskControlService.PlatformControl c = new TaskControlService.PlatformControl();
        c.setCode(code);
        c.setState(state);
        c.setReason(reason);
        return c;
    }

    private static PipelineDefinitionService.DefinitionRequest draft(String text) {
        PipelineDefinitionService.DefinitionRequest request = new PipelineDefinitionService.DefinitionRequest();
        request.setPipelineKey(KEY);
        request.setText(text);
        return request;
    }

    private static PipelineDefinitionService.TaskSwitchRequest switchOf(String code, Boolean enabled) {
        PipelineDefinitionService.TaskSwitchRequest s = new PipelineDefinitionService.TaskSwitchRequest();
        s.setCode(code);
        s.setEnabled(enabled);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBlockIsRefusedAtDesignAndSaveTimeAndTheWorkspaceCannotSwitchItOn() {
        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM, "qa-platform-admin");
        ResponseDto blocked = this.controls.platformState(control("sample", "blocked", "Under review"), TENANT);
        assertThat(blocked.getStatus()).as(blocked.getMessage()).isEqualTo("SUCCESS");
        assertThat(blocked.getMessage()).isEqualTo("Sample rows is blocked for this workspace: its pipelines cannot use it and its "
            + "administrator cannot switch it on.");
        assertThat((Map<String, Object>) blocked.getData()).containsEntry("effectiveState", "blocked").containsEntry("blockedByName", "QA platform admin")
            .containsEntry("blockReason", "Under review");

        // The platform administrator reads the registry as that workspace has it; another workspace is untouched.
        List<Map<String, Object>> theirs = (List<Map<String, Object>>) this.definitions.tasks(TENANT).getData();
        assertThat(theirs.stream().filter(e -> "sample".equals(e.get("code"))).findFirst().get()).containsEntry("blocked", true);
        List<Map<String, Object>> other = (List<Map<String, Object>>) this.definitions.tasks(OTHER).getData();
        assertThat(other.stream().filter(e -> "sample".equals(e.get("code"))).findFirst().get()).containsEntry("blocked", false)
            .containsEntry("effectiveState", "on");

        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@riverside");
        String refused = "the task 'sample' (Sample rows) is blocked for this workspace by a platform administrator: Under review";
        assertThat(this.definitions.validate(draft(USES_SAMPLE)).getMessage()).contains(refused);
        assertThat(this.definitions.save(draft(USES_SAMPLE)).getMessage()).contains(refused);
        assertThat(this.definitions.switchTask(switchOf("sample", true)).getMessage())
            .isEqualTo("'sample' is blocked for this workspace by a platform administrator; only a platform administrator can unblock it.");
        assertThat(this.definitions.switchTask(switchOf("sample", null)).getStatus()).isEqualTo("ERROR");
        // A workspace administrator's tenantId names nothing: their own workspace, blocked.
        List<Map<String, Object>> mine = (List<Map<String, Object>>) this.definitions.tasks(OTHER).getData();
        assertThat(mine.stream().filter(e -> "sample".equals(e.get("code"))).findFirst().get()).containsEntry("blocked", true);
        assertThat(this.controls.platformState(control("sample", "default", null), TENANT).getMessage())
            .isEqualTo("Only a platform administrator controls a task for a workspace.");

        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM, "qa-platform-admin");
        assertThat(this.controls.platformState(control("sample", "disabled", null), TENANT).getMessage())
            .isEqualTo("Sample rows is off for this workspace; its administrator may change that.");
        assertThat(this.overrides.blocks(TENANT)).isEmpty();

        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@riverside");
        assertThat(this.definitions.switchTask(switchOf("sample", true)).getMessage()).isEqualTo("'sample' is switched on in this workspace.");
        assertThat(this.definitions.validate(draft(USES_SAMPLE)).getStatus()).isEqualTo("SUCCESS");

        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM, "qa-platform-admin");
        assertThat(this.controls.platformState(control("sample", "default", "Review done"), TENANT).getMessage())
            .isEqualTo("Sample rows is back to its default for this workspace: on, not blocked.");
        assertThat(this.overrides.overrides(TENANT)).isEmpty();

        List<Map<String, Object>> history = (List<Map<String, Object>>) this.controls.history("sample", TENANT).getData();
        assertThat(history).extracting(h -> h.get("action") + " " + h.get("from") + ">" + h.get("to") + " " + h.get("byRole"))
            .containsExactly("default on>default PLATFORM_ADMIN", "enable off>on TENANT_ADMIN", "disable blocked>off PLATFORM_ADMIN",
                "block default>blocked PLATFORM_ADMIN");
        assertThat(history.get(0)).containsEntry("reason", "Review done").containsEntry("byName", "QA platform admin");
        assertThat(history.get(3)).containsEntry("reason", "Under review");
        assertThat((List<?>) this.controls.history(null, OTHER).getData()).isEmpty();

        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@riverside");
        assertThat((List<?>) this.controls.history(null, OTHER).getData()).as("a workspace admin reads their own").hasSize(4);
    }

    @Test
    void legacyAnUnknownTaskAWorkspaceThatIsNotAndAStateThatIsNotAreRefused() {
        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM, "qa-platform-admin");
        assertThat(this.controls.platformState(control("legacy", "blocked", null), TENANT).getMessage())
            .isEqualTo("'legacy' cannot be switched or blocked: existing pipelines always stay runnable.");
        assertThat(this.controls.platformState(control("nosuch", "blocked", null), TENANT).getMessage()).isEqualTo("No task 'nosuch' is registered.");
        assertThat(this.controls.platformState(control("sample", "blocked", null), 777L).getMessage()).isEqualTo("Workspace 777 is not a workspace.");
        assertThat(this.controls.platformState(control("sample", "blocked", null), null).getMessage()).isEqualTo("Pick the workspace (tenantId).");
        assertThat(this.controls.platformState(control("sample", "paused", null), TENANT).getMessage())
            .isEqualTo("state is enabled, disabled, blocked or default.");
        assertThat(this.controls.history(null, null).getMessage())
            .isEqualTo("Say which workspace (tenantId): a platform administrator reads one workspace's changes at a time.");
        assertThat(this.overrides.audit).isEmpty();
    }
}
