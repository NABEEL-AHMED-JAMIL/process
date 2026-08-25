package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.QueryScheduleDto;
import process.model.dto.ResponseDto;
import process.model.enums.DatabaseType;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.pojo.QuerySchedule;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.model.repository.QueryScheduleRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.UserNameResolver;

import java.sql.Timestamp;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryScheduleServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @Mock
    private QueryScheduleRepository queryScheduleRepository;
    @Mock
    private QueryDefinitionRepository queryDefinitionRepository;
    @Mock
    private DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    @Mock
    private TenantFilterHelper tenantFilterHelper;

    @Mock
    private UserNameResolver userNameResolver;

    private QueryScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new QueryScheduleServiceImpl(this.queryScheduleRepository, this.queryDefinitionRepository,
            this.databaseConnectionProfileRepository, this.tenantFilterHelper, this.userNameResolver);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
    }

    private QuerySchedule scheduleOwnedBy(long tenantId) {
        QuerySchedule schedule = new QuerySchedule();
        schedule.setScheduleId(800L);
        schedule.setTenantId(tenantId);
        schedule.setQueryId(700L);
        schedule.setDatabaseConnectionProfileId(500L);
        schedule.setOutputBucket("bucket");
        schedule.setOutputPrefix("");
        schedule.setOutputFileNameTemplate("export_{date}");
        schedule.setIntervalMinutes(60);
        schedule.setNextRunAt(new Timestamp(System.currentTimeMillis()));
        schedule.setStatus(Status.Active);
        return schedule;
    }

    private QueryDefinition queryOwnedBy(long tenantId) {
        QueryDefinition query = new QueryDefinition();
        query.setQueryId(700L);
        query.setTenantId(tenantId);
        query.setDatabaseConnectionProfileId(500L);
        query.setStatus(Status.Active);
        return query;
    }

    private DatabaseConnectionProfile profileOwnedBy(long tenantId) {
        DatabaseConnectionProfile profile = new DatabaseConnectionProfile();
        profile.setDatabaseConnectionProfileId(500L);
        profile.setTenantId(tenantId);
        profile.setDatabaseType(DatabaseType.POSTGRES);
        profile.setStatus(Status.Active);
        return profile;
    }

    private QueryScheduleDto validScheduleDto() {
        QueryScheduleDto dto = new QueryScheduleDto();
        dto.setQueryId(700L);
        dto.setDatabaseConnectionProfileId(500L);
        dto.setOutputBucket("bucket");
        dto.setOutputFileNameTemplate("export_{date}");
        dto.setIntervalMinutes(30);
        return dto;
    }

    @Test
    void tenantBCannotFetchTenantAsSchedule() throws Exception {
        when(this.queryScheduleRepository.findById(800L)).thenReturn(Optional.of(this.scheduleOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.fetchScheduleById(800L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void tenantBCannotDeleteTenantAsSchedule() throws Exception {
        when(this.queryScheduleRepository.findById(800L)).thenReturn(Optional.of(this.scheduleOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.deleteSchedule(800L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryScheduleRepository, never()).save(any());
    }

    @Test
    void tenantBCannotUpdateTenantAsSchedule() throws Exception {
        when(this.queryScheduleRepository.findById(800L)).thenReturn(Optional.of(this.scheduleOwnedBy(TENANT_A)));

        QueryScheduleDto dto = this.validScheduleDto();
        dto.setScheduleId(800L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.updateSchedule(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryScheduleRepository, never()).save(any());
    }

    @Test
    void tenantBCannotCreateAScheduleAgainstTenantAsQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.addSchedule(this.validScheduleDto());

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryScheduleRepository, never()).save(any());
    }

    @Test
    void tenantBCannotCreateAScheduleAgainstTenantAsConnectionProfileEvenWithItsOwnQuery() throws Exception {

        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_B)));
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.addSchedule(this.validScheduleDto());

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryScheduleRepository, never()).save(any());
    }

    @Test
    void addingAScheduleAlwaysUsesTheCallersOwnTenantIdNeverAClientSuppliedOne() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A)));
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        when(this.queryScheduleRepository.save(any())).thenAnswer(invocation -> {
            QuerySchedule saved = invocation.getArgument(0);
            saved.setScheduleId(999L);
            return saved;
        });

        this.actAsTenant(TENANT_A);
        this.service.addSchedule(this.validScheduleDto());

        ArgumentCaptor<QuerySchedule> captor = ArgumentCaptor.forClass(QuerySchedule.class);
        verify(this.queryScheduleRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void platformAdminCannotOwnAScheduleDirectly() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@example.com");

        ResponseDto response = this.service.addSchedule(this.validScheduleDto());

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryScheduleRepository, never()).save(any());
    }

    @Test
    void findDueSchedulesIsUnscopedByDesignButEachRowStillCarriesItsOwnTenantId() {

        QuerySchedule dueForA = this.scheduleOwnedBy(TENANT_A);
        QuerySchedule dueForB = this.scheduleOwnedBy(TENANT_B);
        dueForB.setScheduleId(801L);
        when(this.queryScheduleRepository.findDueSchedules(any())).thenReturn(java.util.Arrays.asList(dueForA, dueForB));

        java.util.List<QuerySchedule> due = this.service.findDueSchedules(new Timestamp(System.currentTimeMillis()));

        assertThat(due).extracting(QuerySchedule::getTenantId).containsExactlyInAnyOrder(TENANT_A, TENANT_B);
    }
}
