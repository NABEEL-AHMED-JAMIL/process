package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.QueryExecutionRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.DatabaseType;
import process.model.enums.QueryExecutionStatus;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.pojo.QueryExecution;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.model.repository.QueryExecutionRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An execution record (and the "Run" action itself) belonging to Tenant A must never be
 * readable by, or runnable on behalf of, Tenant B -- including a manual "Run" request where
 * Tenant B supplies Tenant A's queryId directly (never trust a client-supplied resource id, per
 * the design review). executeForSchedule is exercised separately in
 * QueryScheduleServiceImplTenantIsolationTest's findDueSchedules test and ProcessCron's own
 * integration, since its tenant scoping comes from the caller (ProcessCron) pre-setting
 * TenantContext from the schedule row, not from anything checked inside this method itself.
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class QueryExecutionServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @Mock
    private QueryExecutionRepository queryExecutionRepository;
    @Mock
    private QueryDefinitionRepository queryDefinitionRepository;
    @Mock
    private DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    @Mock
    private TenantFilterHelper tenantFilterHelper;
    @Mock
    private QueryExecutionRunner queryExecutionRunner;

    private QueryExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new QueryExecutionServiceImpl(this.queryExecutionRepository, this.queryDefinitionRepository,
            this.databaseConnectionProfileRepository, this.tenantFilterHelper, this.queryExecutionRunner);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
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

    private QueryExecution executionOwnedBy(long tenantId) {
        QueryExecution execution = new QueryExecution();
        execution.setExecutionId(900L);
        execution.setTenantId(tenantId);
        execution.setQueryId(700L);
        execution.setStatus(QueryExecutionStatus.SUCCESS);
        execution.setRowCount(10L);
        return execution;
    }

    private QueryExecutionRequestDto validRunRequest() {
        QueryExecutionRequestDto request = new QueryExecutionRequestDto();
        request.setQueryId(700L);
        request.setOutputBucket("bucket");
        request.setOutputFileName("export");
        return request;
    }

    @Test
    void tenantBCannotRunTenantAsQueryByGuessingItsId() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.execute(this.validRunRequest());

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryExecutionRunner, never()).runAndRecord(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tenantBCannotRunItsOwnQueryAgainstTenantAsConnectionProfileByOverridingIt() throws Exception {
        // Tenant B owns the query itself but the request tries to override which connection
        // profile executes it with Tenant A's id -- must still be rejected.
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_B)));
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        QueryExecutionRequestDto request = this.validRunRequest();
        request.setDatabaseConnectionProfileId(500L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.execute(request);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryExecutionRunner, never()).runAndRecord(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tenantBCannotFetchTenantAsExecutionRecordById() throws Exception {
        when(this.queryExecutionRepository.findById(900L)).thenReturn(Optional.of(this.executionOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.fetchExecutionById(900L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void tenantBCannotListExecutionsForTenantAsQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.fetchExecutionsByQueryId(700L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryExecutionRepository, never()).findByQueryIdOrderByExecutionIdDesc(any());
    }

    @Test
    void tenantACanRunItsOwnQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A)));
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        QueryExecution recorded = this.executionOwnedBy(TENANT_A);
        when(this.queryExecutionRunner.runAndRecord(any(), any(), any(), any(), any(), any())).thenReturn(recorded);

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.execute(this.validRunRequest());

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryExecutionRunner).runAndRecord(any(), any(), any(), any(), any(), any());
    }
}
