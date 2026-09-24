package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.LookupDataDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.LookupData;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.LookupDataRepository;
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
 * MIG-165: a task's home page and group are bigint foreign keys to lookup_data since V70.3, and the
 * service holds what the key cannot: the row must be of the right family and of the task's own workspace.
 * Before, any string was stored -- and a home page is resolved to its URL at dispatch, so another
 * workspace's id put that workspace's URL into this one's job payload.
 */
class SourceTaskReferenceTest {

    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;

    private final SourceTaskRepository tasks = mock(SourceTaskRepository.class);
    private final SourceTaskTypeRepository types = mock(SourceTaskTypeRepository.class);
    private final LookupDataRepository lookups = mock(LookupDataRepository.class);
    private SourceTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SourceTaskServiceImpl(null, null, null, this.tasks, this.types, mock(TenantFilterHelper.class),
            new TaskPayloadLocationUtil(), null, TestNotifications.recording(null, null, null, null), null);
        ReflectionTestUtils.setField(this.service, "lookupDataRepository", this.lookups);
        TenantContext.set(MINE, "TENANT_ADMIN", 42L, "ops@medaxis.test");
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(7300L);
        type.setTenantId(MINE);
        type.setStatus(Status.Active);
        when(this.types.findSourceTaskTypeBySourceTaskTypeIdAndStatus(7300L, Status.Active)).thenReturn(Optional.of(type));
        LookupData homePages = row(1017L, "PIPELINE_HOME_PAGES", null, null);
        LookupData groups = row(1033L, "TASK_GROUPS", null, null);
        this.lookup(row(1275L, "MedAxis Home", homePages, MINE));
        this.lookup(row(1274L, "CareBridge Home", homePages, THEIRS));
        this.lookup(row(1280L, "Nightly", groups, MINE));
        this.lookup(row(1002L, "QUEUE_FETCH_LIMIT", null, null));
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
        assertThat(this.service.addSourceTask(task(null, "1002")).getMessage()).contains("task groups");
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

    @Test
    void aHomePageALiveTaskUsesCannotBeDeleted() throws Exception {
        LookupDataRepository settingsLookups = mock(LookupDataRepository.class);
        SourceTaskRepository settingsTasks = mock(SourceTaskRepository.class);
        LookupData home = row(1275L, "MedAxis Home", row(1017L, "PIPELINE_HOME_PAGES", null, null), MINE);
        when(settingsLookups.findById(1275L)).thenReturn(Optional.of(home));
        when(settingsTasks.countLiveTasksReferencing(1275L)).thenReturn(2L);
        SettingServiceImpl settings = new SettingServiceImpl(settingsLookups, null, null, null, null, null, null, null, null,
            mock(LookupDataCacheService.class), null);
        ReflectionTestUtils.setField(settings, "sourceTaskRepository", settingsTasks);
        LookupDataDto delete = new LookupDataDto();
        delete.setLookupId(1275L);

        ResponseDto response = settings.deleteLookupData(delete);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo("2 tasks still use \"MedAxis Home\". Change those tasks first.");
        verify(settingsLookups, never()).deleteById(any());
    }

    private SourceTask saved() {
        ArgumentCaptor<SourceTask> saved = ArgumentCaptor.forClass(SourceTask.class);
        verify(this.tasks).save(saved.capture());
        return saved.getValue();
    }

    private void lookup(LookupData row) {
        when(this.lookups.findById(row.getLookupId())).thenReturn(Optional.of(row));
    }

    private static LookupData row(Long id, String type, LookupData parent, Long tenantId) {
        LookupData row = new LookupData();
        row.setLookupId(id);
        row.setLookupType(type);
        row.setParent(parent);
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
