package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may run SQL that arrives on the request rather than out of a saved query.
 *
 * Authoring a saved query is a tenant admin's job, but /queries/preview and /queries/validate sit
 * at TENANT_USER so that somebody with a read-only account can still run the queries their admin
 * approved. Reading the SQL straight off the request undoes that: it is every table the
 * connection's database user can reach, under a role that may not author anything. The saved-query
 * branch stays open to a tenant user, the ad-hoc branch does not.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class QueryDefinitionAdHocSqlRoleTest {

    private static final long TENANT_A = 1001L;

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
            .thenReturn(QueryValidator.ValidationResult.valid("SELECT * FROM employee_compensation"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAs(String userRole) {
        TenantContext.set(TENANT_A, userRole, 9000L, "someone@example.com");
    }

    private QueryDefinitionDto adHocRequest() {
        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryText("SELECT * FROM employee_compensation");
        dto.setDatabaseConnectionProfileId(500L);
        return dto;
    }

    private QueryDefinition savedQuery() {
        QueryDefinition query = new QueryDefinition();
        query.setQueryId(700L);
        query.setTenantId(TENANT_A);
        query.setQueryName("Approved Query");
        query.setQueryText("encrypted-sql");
        query.setDatabaseConnectionProfileId(500L);
        query.setStatus(Status.Active);
        return query;
    }

    private DatabaseConnectionProfile savedProfile() {
        DatabaseConnectionProfile profile = new DatabaseConnectionProfile();
        profile.setDatabaseConnectionProfileId(500L);
        profile.setTenantId(TENANT_A);
        profile.setDatabaseType(DatabaseType.POSTGRES);
        profile.setStatus(Status.Active);
        return profile;
    }

    @Test
    void aTenantUserCannotPreviewSqlThatCameInOnTheRequest() throws Exception {
        this.actAs("TENANT_USER");
        ResponseDto response = this.service.previewQuery(this.adHocRequest());

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionProfileRepository, never()).findById(any());
        verify(this.databaseConnectionFactory, never()).openConnection(any());
    }

    @Test
    void aTenantUserCannotValidateSqlThatCameInOnTheRequest() throws Exception {
        this.actAs("TENANT_USER");
        ResponseDto response = this.service.validateQuery(this.adHocRequest());

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void aTenantAdminMayStillPreviewAdHocSql() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.savedProfile()));
        when(this.databaseConnectionFactory.openConnection(any()))
            .thenThrow(new SQLException("connection refused"));

        this.actAs("TENANT_ADMIN");
        ResponseDto response = this.service.previewQuery(this.adHocRequest());

        // The role gate is passed; the preview only stops at the database being unreachable.
        assertThat(response.getMessage()).contains("Preview failed");
    }

    @Test
    void aTenantUserMayStillPreviewASavedQueryItOwns() throws Exception {
        when(this.queryDefinitionRepository.findById(700L)).thenReturn(Optional.of(this.savedQuery()));
        when(this.encryptionUtil.decrypt("encrypted-sql")).thenReturn("SELECT 1");
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.savedProfile()));
        when(this.databaseConnectionFactory.openConnection(any()))
            .thenThrow(new SQLException("connection refused"));

        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryId(700L);

        this.actAs("TENANT_USER");
        ResponseDto response = this.service.previewQuery(dto);

        assertThat(response.getMessage()).contains("Preview failed");
    }
}
