package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.identity.TestIdentity;
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
import process.model.projection.SourceTaskTypeProjection;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.TaskPayloadLocationUtil;
import process.util.UserNameResolver;
import process.util.excel.BulkExcel;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import process.notifications.TestNotifications;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * UX review 2026-10-12 (P3): a new pipeline starts from what it does, not from Kafka. addSourceTask no longer requires the
 * event source: no payload is the empty one a step pipeline carries, and no topic is the workspace's own active topic that
 * most pipelines use. A workspace with none is told what to do; a topic or payload that IS given is checked as before.
 */
@ExtendWith(MockitoExtension.class)
public class SourceTaskEventSourceOptionalTest {

    private static final long TENANT_A = 1001L;
    private static final long BUSY_TOPIC = 17L;
    private static final long QUIET_TOPIC = 12L;

    @Mock private BulkExcel bulkExcel;
    @Mock private QueryService queryService;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private TenantRepository tenantRepository;
    @Mock private TestNotifications.NoticeSink notificationCenterService;
    @Mock private UserNameResolver userNameResolver;
    @Mock private EntityManager entityManager;

    private SourceTaskServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new SourceTaskServiceImpl(this.bulkExcel, this.queryService,
            this.sourceJobRepository, this.sourceTaskRepository, this.sourceTaskTypeRepository,
            this.tenantFilterHelper, new TaskPayloadLocationUtil(), TestIdentity.over(null, this.tenantRepository),
            TestNotifications.recording(null, this.notificationCenterService, null), this.userNameResolver);
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

    private static SourceTaskTypeProjection topic(long id, Status status, Long links) {
        SourceTaskTypeProjection topic = mock(SourceTaskTypeProjection.class);
        lenient().when(topic.getSourceTaskTypeId()).thenReturn(id);
        lenient().when(topic.getStatus()).thenReturn(status);
        lenient().when(topic.getTotalTaskLink()).thenReturn(links);
        return topic;
    }

    private void topicResolves(long id) {
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setSourceTaskTypeId(id);
        sourceTaskType.setTenantId(TENANT_A);
        sourceTaskType.setStatus(Status.Active);
        when(this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(id, Status.Active))
            .thenReturn(Optional.of(sourceTaskType));
    }

    private SourceTask savedTask() {
        ArgumentCaptor<SourceTask> captor = ArgumentCaptor.forClass(SourceTask.class);
        verify(this.sourceTaskRepository).save(captor.capture());
        return captor.getValue();
    }

    private static SourceTaskDto named(String name) {
        SourceTaskDto dto = new SourceTaskDto();
        dto.setTaskName(name);
        dto.setPipelineId("PATIENT_360");
        return dto;
    }

    @Test
    void aPipelineWithNoEventSourceGetsTheWorkspacesBusiestActiveTopicAndAnEmptyPayload() throws Exception {
        List<SourceTaskTypeProjection> topics = Arrays.asList(
            topic(QUIET_TOPIC, Status.Active, 1L), topic(BUSY_TOPIC, Status.Active, 12L), topic(99L, Status.Inactive, 40L));
        when(this.sourceTaskTypeRepository.fetchAllSourceTaskTypeForTenant(TENANT_A)).thenReturn(topics);
        this.topicResolves(BUSY_TOPIC);

        ResponseDto response = this.service.addSourceTask(named("Patient 360 (from steps)"));

        assertThat(response.getStatus()).as(response.getMessage()).isEqualTo("SUCCESS");
        SourceTask saved = this.savedTask();
        assertThat(saved.getSourceTaskType().getSourceTaskTypeId()).isEqualTo(BUSY_TOPIC);
        assertThat(saved.getTaskPayload()).isEqualTo(SourceTaskServiceImpl.EMPTY_PAYLOAD);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void onATieTheLowestTopicIdWins() {
        List<SourceTaskTypeProjection> topics = Arrays.asList(topic(BUSY_TOPIC, Status.Active, 3L), topic(QUIET_TOPIC, Status.Active, 3L));
        when(this.sourceTaskTypeRepository.fetchAllSourceTaskTypeForTenant(TENANT_A)).thenReturn(topics);
        assertThat(this.service.defaultTopicFor(TENANT_A)).contains(QUIET_TOPIC);
        assertThat(this.service.defaultTopicFor(null)).isEmpty();
    }

    @Test
    void aWorkspaceWithNoActiveTopicIsToldWhatToDoAndNothingIsSaved() throws Exception {
        List<SourceTaskTypeProjection> topics = Collections.singletonList(topic(99L, Status.Inactive, 4L));
        when(this.sourceTaskTypeRepository.fetchAllSourceTaskTypeForTenant(TENANT_A)).thenReturn(topics);

        ResponseDto response = this.service.addSourceTask(named("No topic"));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("no event source yet").contains("Advanced: event source");
        verify(this.sourceTaskRepository, never()).save(any());
    }

    @Test
    void aTopicThatIsGivenIsCheckedAsBeforeAndNoDefaultIsLookedUp() throws Exception {
        SourceTaskDto dto = named("Picked topic");
        SourceTaskTypeDto picked = new SourceTaskTypeDto();
        picked.setSourceTaskTypeId(555L);
        dto.setSourceTaskType(picked);
        when(this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(555L, Status.Active))
            .thenReturn(Optional.empty());

        ResponseDto response = this.service.addSourceTask(dto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo("Provided sourceTaskTypeId not found.");
        verify(this.sourceTaskTypeRepository, never()).fetchAllSourceTaskTypeForTenant(any());
    }

    @Test
    void aNameIsStillRequired() throws Exception {
        assertThat(this.service.addSourceTask(named(null)).getMessage()).isEqualTo("SourceTask taskName missing.");
    }
}
