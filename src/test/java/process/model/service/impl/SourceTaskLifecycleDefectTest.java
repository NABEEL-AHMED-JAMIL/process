package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import process.model.dto.PagingDto;
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
import process.util.PagingUtil;
import process.util.TaskPayloadLocationUtil;
import process.util.UserNameResolver;
import process.util.excel.BulkExcel;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import java.math.BigInteger;
import process.notifications.TestNotifications;

/**
 * Three things a task's own endpoints got wrong about the request they were given.
 *
 * addSourceTask ignored the taskStatus it was sent and always wrote Active, so the console's
 * Clone button produced a live, bindable task while telling the operator it was parked.
 * deleteSourceTask marked the task deleted only when the request happened to carry a taskStatus
 * -- a field it does not require -- and reported success either way. And
 * fetchAllLinkJobsWithSourceTaskId accepted page, limit, columnName and order, used none of
 * them, and returned no paging block for a paginator to read.
 */
@ExtendWith(MockitoExtension.class)
public class SourceTaskLifecycleDefectTest {

    private static final long TENANT_A = 1001L;
    private static final long TASK_TYPE_ID = 17L;
    private static final long TASK_ID = 1043L;

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
        this.service = new SourceTaskServiceImpl(this.bulkExcel, this.queryService,
            this.sourceJobRepository, this.sourceTaskRepository, this.sourceTaskTypeRepository,
            this.tenantFilterHelper, new TaskPayloadLocationUtil(), this.tenantRepository,
            TestNotifications.inProcess(null, null, this.notificationCenterService, null), this.userNameResolver);
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

    // ---- fixtures ---------------------------------------------------------------------------

    private static SourceTaskDto creationDto() {
        SourceTaskTypeDto sourceTaskType = new SourceTaskTypeDto();
        sourceTaskType.setSourceTaskTypeId(TASK_TYPE_ID);
        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskName("orders nightly (copy)");
        sourceTaskDto.setTaskPayload("<task><bucket>a-bucket</bucket></task>");
        sourceTaskDto.setSourceTaskType(sourceTaskType);
        return sourceTaskDto;
    }

    private void taskTypeResolves() {
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setSourceTaskTypeId(TASK_TYPE_ID);
        sourceTaskType.setTenantId(TENANT_A);
        sourceTaskType.setStatus(Status.Active);
        sourceTaskType.setServiceName("tenant-a-ingest");
        when(this.sourceTaskTypeRepository.findSourceTaskTypeBySourceTaskTypeIdAndStatus(
            TASK_TYPE_ID, Status.Active)).thenReturn(Optional.of(sourceTaskType));
    }

    private SourceTask savedTask() {
        ArgumentCaptor<SourceTask> captor = ArgumentCaptor.forClass(SourceTask.class);
        verify(this.sourceTaskRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---- the requested status is honoured on create -------------------------------------------

    /**
     * Being Active is what makes a task bindable: updateSourceJob refuses anything else, and the
     * bulk job upload builds its valid-task list from Active tasks only. A clone the operator was
     * told is parked could therefore be attached to live jobs.
     */
    @Test
    void createHonoursAnExplicitlyInactiveStatus() throws Exception {
        this.taskTypeResolves();
        SourceTaskDto sourceTaskDto = creationDto();
        sourceTaskDto.setTaskStatus(Status.Inactive);

        ResponseDto response = this.service.addSourceTask(sourceTaskDto);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.savedTask().getTaskStatus()).isEqualTo(Status.Inactive);
    }

    @Test
    void createStillDefaultsToActiveWhenNoStatusIsSent() throws Exception {
        this.taskTypeResolves();

        ResponseDto response = this.service.addSourceTask(creationDto());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.savedTask().getTaskStatus()).isEqualTo(Status.Active);
    }

    @Test
    void createRefusesToBeBornDeleted() throws Exception {
        SourceTaskDto sourceTaskDto = creationDto();
        sourceTaskDto.setTaskStatus(Status.Delete);

        ResponseDto response = this.service.addSourceTask(sourceTaskDto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("cannot be created as Delete");
        verify(this.sourceTaskRepository, never()).save(any());
    }

    // ---- delete means delete -------------------------------------------------------------------

    /**
     * taskDetailId is the only field this endpoint declares as required, and a request carrying
     * just that one was answered "SourceTask successfully deleted with ID 1043." while the task
     * stayed Active in every list and every picker.
     */
    @Test
    void deleteMarksTheTaskEvenWhenTheRequestOmitsTaskStatus() throws Exception {
        SourceTask existing = new SourceTask();
        existing.setTaskDetailId(TASK_ID);
        existing.setTenantId(TENANT_A);
        existing.setTaskName("orders nightly");
        existing.setTaskStatus(Status.Active);
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(existing));
        when(this.sourceJobRepository.countLiveJobsForTask(TASK_ID)).thenReturn(0L);

        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskDetailId(TASK_ID);

        ResponseDto response = this.service.deleteSourceTask(sourceTaskDto);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.savedTask().getTaskStatus()).isEqualTo(Status.Delete);
    }

    /** The live-jobs guard is what the unconditional mark must not step past. */
    @Test
    void deleteStillRefusesATaskThatJobsAreUsing() throws Exception {
        SourceTask existing = new SourceTask();
        existing.setTaskDetailId(TASK_ID);
        existing.setTenantId(TENANT_A);
        existing.setTaskName("orders nightly");
        existing.setTaskStatus(Status.Active);
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(existing));
        when(this.sourceJobRepository.countLiveJobsForTask(TASK_ID)).thenReturn(3L);

        SourceTaskDto sourceTaskDto = new SourceTaskDto();
        sourceTaskDto.setTaskDetailId(TASK_ID);
        sourceTaskDto.setTaskStatus(Status.Delete);

        ResponseDto response = this.service.deleteSourceTask(sourceTaskDto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("still has 3 jobs using it");
        verify(this.sourceTaskRepository, never()).save(any());
        verify(this.sourceJobRepository, never()).statusChangeSourceJobWithSourceTaskId(any(), anyString());
    }

    // ---- the linked-jobs endpoint honours the paging it advertises ------------------------------

    /**
     * It took a Pageable and never referenced it, calling the single-argument executeQuery
     * overload, so page 2 was page 1 again and a client asking for a 500-row cap got everything.
     * The count went through executeQuery too -- a List, so never null -- which made the guard
     * above it dead and the query a wasted round trip.
     */
    @Test
    void theLinkedJobsEndpointPagesAndCountsWhatItWasAsked() throws Exception {
        Pageable paging = PagingUtil.ApplyPaging("sj.job_id", "asc", 2L, 25L);
        this.queryBuilderReturnsDistinguishableSql();
        when(this.queryService.executeQueryForSingleResult("count-sql")).thenReturn(BigInteger.valueOf(400L));
        when(this.queryService.executeQuery("rows-sql", paging)).thenReturn(oneLinkedJobRow());

        ResponseDto response = this.service.fetchAllLinkJobsWithSourceTaskId(
            TASK_ID, null, null, "sj.job_id", "asc", paging, null);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        // The rows came from the paging overload -- the un-paged one was never used for them.
        verify(this.queryService).executeQuery("rows-sql", paging);
        verify(this.queryService, never()).executeQuery(anyString());
        // and the response says which page of how many this is.
        PagingDto pagingDto = (PagingDto) response.getPaging();
        assertThat(pagingDto).isNotNull();
        assertThat(pagingDto.getTotalRecord()).isEqualTo(400L);
        assertThat(pagingDto.getCurrentPage()).isEqualTo(2L);
        assertThat(pagingDto.getPageSize()).isEqualTo(25L);
    }

    /** The row query carries an order by, or two consecutive pages can repeat and skip rows. */
    @Test
    void theLinkedJobsRowQueryIsOrdered() {
        // The real builder, not the mock: this is about the SQL text it emits, and it needs no
        // EntityManager to produce it.
        QueryService real = new QueryService();
        String query = real.fetchAllLinkJobsWithSourceTaskQuery(false, TASK_ID, null, null, "sj.job_name", "desc", null);
        assertThat(query).contains("order by sj.job_name desc");
        // An unknown column falls back rather than reaching the database as typed.
        String injected = real.fetchAllLinkJobsWithSourceTaskQuery(false, TASK_ID, null, null,
            "sj.job_id; drop table source_job", "asc", null);
        assertThat(injected).contains("order by sj.job_id asc");
        assertThat(injected).doesNotContain("drop table");
        // The count query stays unordered -- ordering a count is pure cost.
        assertThat(real.fetchAllLinkJobsWithSourceTaskQuery(true, TASK_ID, null, null, "sj.job_name", "desc", null))
            .doesNotContain("order by");
    }

    /**
     * Two different strings for the count and the rows, so which builder overload produced which
     * query is visible in the assertions rather than inferred. The seven-argument one is the
     * ordered form the rows need; a count has no business carrying an order by.
     */
    private void queryBuilderReturnsDistinguishableSql() {
        when(this.queryService.fetchAllLinkJobsWithSourceTaskQuery(eq(true), eq(TASK_ID), any(), any(), any()))
            .thenReturn("count-sql");
        when(this.queryService.fetchAllLinkJobsWithSourceTaskQuery(eq(false), eq(TASK_ID), any(), any(), any(), any(), any()))
            .thenReturn("rows-sql");
    }

    private static List<Object[]> oneLinkedJobRow() {
        return Collections.singletonList(new Object[] {
            BigInteger.valueOf(5073L), "Pacific hurricanes", "Active", "Auto", "Completed",
            "2026-08-27 12:25:03", 1, Timestamp.valueOf("2026-08-27 12:25:03").toString() });
    }

    @Test
    void anEmptyPageStillReportsTheTotal() throws Exception {
        Pageable paging = PagingUtil.ApplyPaging("sj.job_id", "asc", 99L, 25L);
        this.queryBuilderReturnsDistinguishableSql();
        when(this.queryService.executeQueryForSingleResult("count-sql")).thenReturn(BigInteger.valueOf(400L));
        when(this.queryService.executeQuery("rows-sql", paging)).thenReturn(Arrays.asList());

        ResponseDto response = this.service.fetchAllLinkJobsWithSourceTaskId(
            TASK_ID, null, null, "sj.job_id", "asc", paging, null);

        // Past the end is an empty list, not an error.
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat((List<?>) response.getData()).isEmpty();
    }

}
