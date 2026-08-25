package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.query.DatabaseConnectionFactory;
import process.engine.query.QueryValidator;
import process.model.dto.QueryDefinitionDto;
import process.model.dto.ResponseDto;
import process.model.enums.DatabaseType;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.UserNameResolver;
import process.util.EncryptionUtil;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryDefinitionServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @Mock
    private QueryDefinitionRepository queryDefinitionRepository;
    @Mock
    private DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private TenantFilterHelper tenantFilterHelper;
    @Mock
    private QueryValidator queryValidator;
    @Mock
    private DatabaseConnectionFactory databaseConnectionFactory;
    @Mock
    private UserNameResolver userNameResolver;

    private QueryDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new QueryDefinitionServiceImpl(this.queryDefinitionRepository,
            this.databaseConnectionProfileRepository, this.encryptionUtil, this.tenantFilterHelper,
            this.queryValidator, this.databaseConnectionFactory, this.userNameResolver);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
        lenient().when(this.queryValidator.validate(anyString()))
            .thenReturn(QueryValidator.ValidationResult.valid("SELECT 1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
    }

    private QueryDefinition queryOwnedBy(long tenantId, long connectionProfileId) {
        QueryDefinition query = new QueryDefinition();
        query.setQueryId(700L);
        query.setTenantId(tenantId);
        query.setQueryName("Some Query");
        query.setQueryText("encrypted-sql");
        query.setDatabaseConnectionProfileId(connectionProfileId);
        query.setStatus(Status.Active);
        query.setVersion(1);
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

    @Test
    void tenantBCannotFetchTenantAsQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A, 500L)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.fetchQueryById(700L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void tenantBCannotUpdateTenantAsQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A, 500L)));

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryId(700L);
        dto.setQueryName("hijacked");
        dto.setQueryText("SELECT * FROM anything");
        dto.setDatabaseConnectionProfileId(500L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.updateQuery(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryDefinitionRepository, never()).save(any());
    }

    @Test
    void tenantBCannotDeleteTenantAsQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A, 500L)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.deleteQuery(700L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryDefinitionRepository, never()).save(any());
    }

    @Test
    void tenantBCannotValidateUsingTenantAsSavedQueryText() throws Exception {

        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A, 500L)));

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryId(700L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.validateQuery(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.encryptionUtil, never()).decrypt(any());
    }

    @Test
    void tenantBCannotPreviewUsingTenantAsSavedQuery() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.queryOwnedBy(TENANT_A, 500L)));

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryId(700L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.previewQuery(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionFactory, never()).openConnection(any());
    }

    @Test
    void tenantCannotSaveANewQueryAgainstAConnectionProfileItDoesNotOwn() throws Exception {

        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryName("new query");
        dto.setQueryText("SELECT 1");
        dto.setDatabaseConnectionProfileId(500L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.addQuery(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryDefinitionRepository, never()).save(any());
    }

    @Test
    void addingAQueryAlwaysUsesTheCallersOwnTenantIdNeverAClientSuppliedOne() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        when(this.encryptionUtil.encrypt(any())).thenReturn("cipher");
        when(this.queryDefinitionRepository.save(any())).thenAnswer(invocation -> {
            QueryDefinition saved = invocation.getArgument(0);
            saved.setQueryId(999L);
            return saved;
        });

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryName("new query");
        dto.setQueryText("SELECT 1");
        dto.setDatabaseConnectionProfileId(500L);

        this.actAsTenant(TENANT_A);
        this.service.addQuery(dto);

        ArgumentCaptor<QueryDefinition> captor = ArgumentCaptor.forClass(QueryDefinition.class);
        verify(this.queryDefinitionRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void aQueryThatFailsSqlValidationIsRejectedBeforeAnyOwnershipOrPersistenceWork() throws Exception {
        when(this.queryValidator.validate(anyString()))
            .thenReturn(QueryValidator.ValidationResult.invalid("Only SELECT queries are supported"));

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryName("evil");
        dto.setQueryText("DROP TABLE customers");
        dto.setDatabaseConnectionProfileId(500L);

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.addQuery(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.queryDefinitionRepository, never()).save(any());
        verify(this.databaseConnectionProfileRepository, never()).findById(any());
    }
}
