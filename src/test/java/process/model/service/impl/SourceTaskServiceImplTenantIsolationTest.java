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
 * A SourceTask's task type is not a label: it carries the Kafka topic and the connection
 * profile the job publishes with, so a task bound to another tenant's type both discloses
 * their topology through the task detail screen and can send on their broker. The type was
 * only ever checked for existence and Active status, never for whether the caller may see it.
 *
 * Driven through TenantContext rather than a JWT: the context is what the check actually reads.
 */
@ExtendWith(MockitoExtension.class)
class SourceTaskServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long TYPE_OWNED_BY_B = 17L;
    private static final long TASK_OF_A = 300L;

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
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static SourceTaskType taskTypeOwnedBy(Long tenantId) {
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setSourceTaskTypeId(TYPE_OWNED_BY_B);
        sourceTaskType.setTenantId(tenantId);
        sourceTaskType.setStatus(Status.Active);
        sourceTaskType.setServiceName("tenant-b-ingest");
        sourceTaskType.setQueueTopicPartition("tenant-b-ingest-topic");
        sourceTaskType.setKafkaConnectionProfileId(9L);
        return sourceTaskType;
    }

    private static SourceTaskDto taskDtoLinkedToType() {
        SourceTaskTypeDto sourceTaskType = new SourceTaskTypeDto();
        sourceTaskType.setSourceTaskTypeId(TYPE_OWNED_BY_B);
        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskName("nightly export");
        sourceTaskDto.setTaskPayload("<task><bucket>a-bucket</bucket></task>");
        sourceTaskDto.setSourceTaskType(sourceTaskType);
        return sourceTaskDto;
    }

    private void typeResolvesTo(SourceTaskType sourceTaskType) {
        when(this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            TYPE_OWNED_BY_B, Status.Active)).thenReturn(Optional.of(sourceTaskType));
    }

    // ---- a task must not be bound to another tenant's task type ---------------------------

    @Test
    void addRefusesATaskTypeOwnedByAnotherTenant() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
        this.typeResolvesTo(taskTypeOwnedBy(TENANT_B));

        ResponseDto response = this.service.addSourceTask(taskDtoLinkedToType());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        // Same wording as a genuinely missing id, or the refusal becomes an id oracle.
        assertThat(response.getMessage()).isEqualTo("Provided sourceTaskTypeId not found.");
        verify(this.sourceTaskRepository, never()).save(any());
    }

    @Test
    void updateRefusesATaskTypeOwnedByAnotherTenant() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
        this.typeResolvesTo(taskTypeOwnedBy(TENANT_B));
        SourceTaskDto sourceTaskDto = taskDtoLinkedToType();
        sourceTaskDto.setTaskDetailId(TASK_OF_A);

        ResponseDto response = this.service.updateSourceTask(sourceTaskDto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceTaskRepository, never()).save(any());
    }

    @Test
    void aContextWithNoTenantCannotBindATenantOwnedTaskType() throws Exception {
        // A null tenant must not compare equal to a real one.
        TenantContext.set(null, "TENANT_ADMIN", 1L, "orphan@example.com");
        this.typeResolvesTo(taskTypeOwnedBy(TENANT_B));

        ResponseDto response = this.service.addSourceTask(taskDtoLinkedToType());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceTaskRepository, never()).save(any());
    }

    // ---- what must keep working -----------------------------------------------------------

    @Test
    void addAcceptsTheCallersOwnTaskType() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
        this.typeResolvesTo(taskTypeOwnedBy(TENANT_A));

        ResponseDto response = this.service.addSourceTask(taskDtoLinkedToType());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskRepository).save(any(SourceTask.class));
    }

    @Test
    void addAcceptsAPlatformWideTaskType() throws Exception {
        // A type with no tenant is shared by design and every tenant may link to it.
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
        this.typeResolvesTo(taskTypeOwnedBy(null));

        ResponseDto response = this.service.addSourceTask(taskDtoLinkedToType());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskRepository).save(any(SourceTask.class));
    }

    @Test
    void aPlatformAdminMayLinkAnyTenantsTaskType() throws Exception {
        // The one role that legitimately crosses tenants.
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        this.typeResolvesTo(taskTypeOwnedBy(TENANT_B));
        when(this.tenantRepository.existsById(TENANT_B)).thenReturn(true);
        SourceTaskDto sourceTaskDto = taskDtoLinkedToType();
        sourceTaskDto.setTenantId(TENANT_B);

        ResponseDto response = this.service.addSourceTask(sourceTaskDto);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskRepository).save(any(SourceTask.class));
    }
}
