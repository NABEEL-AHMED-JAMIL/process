package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.pojo.TaskReference;
import process.model.repository.TaskReferenceRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.notifications.TestNotifications;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.TaskPayloadLocationUtil;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-165: a task's home page and group are bigint foreign keys (to lookup_data since V70.3, to task_reference since
 * MIG-167's V141), and the service holds what the key cannot: the row must be of the right kind and of the task's
 * own workspace.
 * Before, any string was stored -- and a home page is resolved to its URL at dispatch, so another
 * workspace's id put that workspace's URL into this one's job payload.
 */
class SourceTaskReferenceTest {

    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;

    private final SourceTaskRepository tasks = mock(SourceTaskRepository.class);
    private final SourceTaskTypeRepository types = mock(SourceTaskTypeRepository.class);
    private final TaskReferenceRepository references = mock(TaskReferenceRepository.class);
    private SourceTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SourceTaskServiceImpl(null, null, null, this.tasks, this.types, mock(TenantFilterHelper.class),
            new TaskPayloadLocationUtil(), null, TestNotifications.recording(null, null, null, null), null);
        ReflectionTestUtils.setField(this.service, "taskReferenceRepository", this.references);
        TenantContext.set(MINE, "TENANT_ADMIN", 42L, "ops@medaxis.test");
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(7300L);
        type.setTenantId(MINE);
        type.setStatus(Status.Active);
        when(this.types.findSourceTaskTypeBySourceTaskTypeIdAndStatus(7300L, Status.Active)).thenReturn(Optional.of(type));
        this.reference(row(1275L, "MedAxis Home", TaskReference.HOME_PAGE, MINE));
        this.reference(row(1274L, "CareBridge Home", TaskReference.HOME_PAGE, THEIRS));
        this.reference(row(1280L, "Nightly", TaskReference.TASK_GROUP, MINE));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aHomePageAndGroupOfTheWorkspaceAreStoredAsIds() throws Exception {
        ResponseDto response = this.service.addSourceTask(task("1275", "1280"));

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        SourceTask saved = this.saved();
        assertThat(saved.getHomePageId()).isEqualTo(1275L);
        assertThat(saved.getGroupId()).isEqualTo(1280L);
    }

    @Test
    void blankIsNoneAndIsStoredAsNull() throws Exception {
        ResponseDto response = this.service.addSourceTask(task("", "  "));

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        assertThat(this.saved().getHomePageId()).isNull();
        assertThat(this.saved().getGroupId()).isNull();
    }

    @Test
    void anotherWorkspacesHomePageIsRefusedLikeOneThatDoesNotExist() throws Exception {
        ResponseDto theirs = this.service.addSourceTask(task("1274", null));
        ResponseDto missing = this.service.addSourceTask(task("99999", null));

        assertThat(theirs.getStatus()).isEqualTo("ERROR");
        assertThat(theirs.getMessage()).isEqualTo("Home page 1274 is not one of this workspace's home pages.");
        assertThat(missing.getMessage()).isEqualTo("Home page 99999 is not one of this workspace's home pages.");
        verify(this.tasks, never()).save(any());
    }

    @Test
    void aRowOfTheWrongFamilyOrNotANumberIsRefused() throws Exception {
        assertThat(this.service.addSourceTask(task("1280", null)).getMessage()).contains("home pages");
        assertThat(this.service.addSourceTask(task(null, "1275")).getMessage()).isEqualTo("Group 1275 is not one of this workspace's task groups.");
        assertThat(this.service.addSourceTask(task("MedAxis Home", null)).getMessage()).contains("home pages");
        verify(this.tasks, never()).save(any());
    }

    @Test
    void anUpdateIsHeldToTheSameRuleAndChangesNothingWhenRefused() throws Exception {
        SourceTask existing = new SourceTask();
        existing.setTaskDetailId(7301L);
        existing.setTenantId(MINE);
        existing.setTaskStatus(Status.Active);
        existing.setHomePageId(1275L);
        when(this.tasks.findById(7301L)).thenReturn(Optional.of(existing));
        SourceTaskDto update = task("1274", null);
        update.setTaskDetailId(7301L);
        update.setTaskName("renamed");

        ResponseDto response = this.service.updateSourceTask(update);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(existing.getHomePageId()).isEqualTo(1275L);
        assertThat(existing.getTaskName()).isNull();
        verify(this.tasks, never()).save(any());
    }

    private SourceTask saved() {
        ArgumentCaptor<SourceTask> saved = ArgumentCaptor.forClass(SourceTask.class);
        verify(this.tasks).save(saved.capture());
        return saved.getValue();
    }

    private void reference(TaskReference row) {
        when(this.references.findById(row.getId())).thenReturn(Optional.of(row));
    }

    private static TaskReference row(Long id, String name, String kind, Long tenantId) {
        TaskReference row = new TaskReference();
        row.setId(id);
        row.setName(name);
        row.setKind(kind);
        row.setTenantId(tenantId);
        return row;
    }

    private static SourceTaskDto task(String homePageId, String groupId) {
        SourceTaskTypeDto type = new SourceTaskTypeDto();
        type.setSourceTaskTypeId(7300L);
        SourceTaskDto task = new SourceTaskDto();
        task.setTaskName("nightly export");
        task.setTaskPayload("<task><bucket>a-bucket</bucket></task>");
        task.setSourceTaskType(type);
        task.setHomePageId(homePageId);
        task.setGroupId(groupId);
        return task;
    }
}
