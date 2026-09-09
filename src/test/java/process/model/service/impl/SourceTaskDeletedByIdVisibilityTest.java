package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.model.service.NotificationCenterService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.TaskPayloadLocationUtil;
import process.util.UserNameResolver;
import process.util.excel.BulkExcel;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Delete on a source task is a soft delete, and findById does not look at task_status. Every
 * list does -- listSourceTaskQuery selects only ('Active', 'Inactive'), downloadListSourceTask
 * and fetchAllLinkSourceTaskWithSourceTaskTypeId exclude 'Delete' -- so the by-id paths were
 * the one place a deleted task was still reachable: it opened in the editor and saving from
 * there put it back to Active, while the jobs deleteSourceTask had cascaded to Delete stayed
 * deleted and invisible.
 *
 * Driven through TenantContext rather than a JWT: the context is what the guards actually read.
 */
@ExtendWith(MockitoExtension.class)
class SourceTaskDeletedByIdVisibilityTest {

    private static final long TENANT_A = 1001L;
    private static final long TASK_ID = 4242L;
    private static final long TASK_TYPE_ID = 17L;

    @Mock private BulkExcel bulkExcel;
    @Mock private QueryService queryService;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private TenantRepository tenantRepository;
    @Mock private NotificationCenterService notificationCenterService;
    @Mock private UserNameResolver userNameResolver;
    @Mock private EntityManager entityManager;

    private SourceTaskServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        // The location util is a pure parser with no collaborators, so the real one is less
        // work than stubbing it and keeps the derived bucket/folder path exercised.
        this.service = new SourceTaskServiceImpl(this.bulkExcel, this.queryService,
            this.sourceJobRepository, this.sourceTaskRepository, this.sourceTaskTypeRepository,
            this.tenantFilterHelper, new TaskPayloadLocationUtil(), this.tenantRepository,
            this.notificationCenterService, this.userNameResolver);
        // entityManager is injected, not constructor-supplied.
        Field em = SourceTaskServiceImpl.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(this.service, this.entityManager);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static SourceTaskType activeTaskType() {
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setSourceTaskTypeId(TASK_TYPE_ID);
        sourceTaskType.setTenantId(TENANT_A);
        sourceTaskType.setStatus(Status.Active);
        sourceTaskType.setServiceName("tenant-a-ingest");
        sourceTaskType.setQueueTopicPartition("tenant-a-ingest-topic");
        sourceTaskType.setKafkaConnectionProfileId(9L);
        return sourceTaskType;
    }

    private static SourceTask taskWithStatus(Status taskStatus) {
        SourceTask sourceTask = new SourceTask();
        sourceTask.setTaskDetailId(TASK_ID);
        sourceTask.setTenantId(TENANT_A);
        sourceTask.setTaskName("nightly export");
        sourceTask.setTaskPayload("<task><bucket>a-bucket</bucket></task>");
        sourceTask.setTaskStatus(taskStatus);
        sourceTask.setSourceTaskType(activeTaskType());
        return sourceTask;
    }

    private static SourceTaskDto editRequest() {
        SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
        sourceTaskTypeDto.setSourceTaskTypeId(TASK_TYPE_ID);
        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskDetailId(TASK_ID);
        sourceTaskDto.setTaskName("nightly export (renamed)");
        sourceTaskDto.setTaskPayload("<task><bucket>a-bucket</bucket></task>");
        // What the editor sends back: the form defaults this field to Active, which is how a
        // save on a deleted task used to resurrect the row.
        sourceTaskDto.setTaskStatus(Status.Active);
        sourceTaskDto.setSourceTaskType(sourceTaskTypeDto);
        return sourceTaskDto;
    }

    private void typeLookupSucceeds() {
        when(this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            TASK_TYPE_ID, Status.Active)).thenReturn(Optional.of(activeTaskType()));
    }

    // ---- a soft-deleted task must read as absent by id -------------------------------------

    @Test
    void fetchByIdRefusesADeletedTask() {
        when(this.sourceTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(taskWithStatus(Status.Delete)));

        ResponseDto response = this.service.fetchSourceTaskWithSourceTaskId(TASK_ID);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        // Same wording as a genuinely missing id, so the refusal is not an id oracle either.
        assertThat(response.getMessage()).isEqualTo(String.format("SourceTask not found with %d.", TASK_ID));
        assertThat(response.getData()).isNull();
    }

    @Test
    void updateRefusesADeletedTaskAndDoesNotResurrectIt() throws Exception {
        this.typeLookupSucceeds();
        when(this.sourceTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(taskWithStatus(Status.Delete)));

        ResponseDto response = this.service.updateSourceTask(editRequest());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo(String.format("SourceTask not found with %d.", TASK_ID));
        verify(this.sourceTaskRepository, never()).save(any(SourceTask.class));
    }

    // ---- what must keep working ------------------------------------------------------------

    @Test
    void fetchByIdStillReturnsAnActiveTask() {
        when(this.sourceTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(taskWithStatus(Status.Active)));

        ResponseDto response = this.service.fetchSourceTaskWithSourceTaskId(TASK_ID);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getData()).isInstanceOf(SourceTaskDto.class);
        assertThat(((SourceTaskDto) response.getData()).getTaskDetailId()).isEqualTo(TASK_ID);
    }

    @Test
    void fetchByIdStillReturnsAnInactiveTask() {
        // Inactive is not deleted -- the list shows it and the editor must keep opening it,
        // otherwise the only way to switch a task back on would be gone.
        when(this.sourceTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(taskWithStatus(Status.Inactive)));

        ResponseDto response = this.service.fetchSourceTaskWithSourceTaskId(TASK_ID);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(((SourceTaskDto) response.getData()).getTaskStatus()).isEqualTo(Status.Inactive);
    }

    @Test
    void updateStillSavesAnInactiveTask() throws Exception {
        this.typeLookupSucceeds();
        when(this.sourceTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(taskWithStatus(Status.Inactive)));

        ResponseDto response = this.service.updateSourceTask(editRequest());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskRepository).save(any(SourceTask.class));
    }
}
