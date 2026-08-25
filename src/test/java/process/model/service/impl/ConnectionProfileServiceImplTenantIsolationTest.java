package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.query.DatabaseConnectionFactory;
import process.model.dto.DatabaseConnectionProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.DatabaseType;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.UserNameResolver;
import process.util.EncryptionUtil;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionProfileServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @Mock
    private DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    @Mock
    private QueryDefinitionRepository queryDefinitionRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private TenantFilterHelper tenantFilterHelper;
    @Mock
    private DatabaseConnectionFactory databaseConnectionFactory;

    @Mock
    private UserNameResolver userNameResolver;

    private ConnectionProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new ConnectionProfileServiceImpl(this.databaseConnectionProfileRepository,
            this.queryDefinitionRepository, this.encryptionUtil, this.tenantFilterHelper,
            this.databaseConnectionFactory, this.userNameResolver);

        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DatabaseConnectionProfile profileOwnedBy(long tenantId) {
        DatabaseConnectionProfile profile = new DatabaseConnectionProfile();
        profile.setDatabaseConnectionProfileId(500L);
        profile.setTenantId(tenantId);
        profile.setProfileName("Tenant's DB");
        profile.setDatabaseType(DatabaseType.POSTGRES);
        profile.setHost("db-host");
        profile.setPort(5432);
        profile.setDatabaseName("tenant_db");
        profile.setUsername("tenant_user");
        profile.setPasswordEncrypted("encrypted-value");
        profile.setStatus(Status.Active);
        return profile;
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
    }

    private void actAsPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@example.com");
    }

    @Test
    void tenantBCannotFetchTenantAsConnectionProfileById() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.fetchConnectionProfileById(500L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).contains("not found");
    }

    @Test
    void tenantACanFetchItsOwnConnectionProfileById() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.fetchConnectionProfileById(500L);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void platformAdminCanFetchAnyTenantsConnectionProfile() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        this.actAsPlatformAdmin();
        ResponseDto response = this.service.fetchConnectionProfileById(500L);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void tenantBCannotUpdateTenantAsConnectionProfile() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setDatabaseConnectionProfileId(500L);
        dto.setProfileName("hijacked");
        dto.setDatabaseType(DatabaseType.POSTGRES);
        dto.setHost("evil-host");
        dto.setPort(5432);
        dto.setDatabaseName("db");
        dto.setUsername("user");

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.updateConnectionProfile(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionProfileRepository, never()).save(any());
    }

    @Test
    void tenantBCannotDeleteTenantAsConnectionProfile() throws Exception {
        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.deleteConnectionProfile(500L);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionProfileRepository, never()).save(any());
    }

    @Test
    void tenantBCannotTestConnectionAgainstTenantAsSavedProfile() throws Exception {

        when(this.databaseConnectionProfileRepository.findById(500L))
            .thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setDatabaseConnectionProfileId(500L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionFactory, never()).openConnection(any());
    }

    @Test
    void platformAdminCannotOwnAConnectionProfileDirectly() throws Exception {
        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setProfileName("orphan");
        dto.setDatabaseType(DatabaseType.POSTGRES);
        dto.setHost("host");
        dto.setPort(5432);
        dto.setDatabaseName("db");
        dto.setUsername("user");
        dto.setPassword("pw");

        this.actAsPlatformAdmin();
        ResponseDto response = this.service.addConnectionProfile(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionProfileRepository, never()).save(any());
    }

    @Test
    void addingAConnectionProfileAlwaysUsesTheCallersOwnTenantIdNeverAClientSuppliedOne() throws Exception {

        when(this.encryptionUtil.encrypt(any())).thenReturn("cipher");
        when(this.databaseConnectionProfileRepository.save(any())).thenAnswer(invocation -> {
            DatabaseConnectionProfile saved = invocation.getArgument(0);
            saved.setDatabaseConnectionProfileId(999L);
            return saved;
        });

        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setProfileName("new profile");
        dto.setDatabaseType(DatabaseType.POSTGRES);
        dto.setHost("host");
        dto.setPort(5432);
        dto.setDatabaseName("db");
        dto.setUsername("user");
        dto.setPassword("pw");

        this.actAsTenant(TENANT_A);
        this.service.addConnectionProfile(dto);

        org.mockito.ArgumentCaptor<DatabaseConnectionProfile> captor =
            org.mockito.ArgumentCaptor.forClass(DatabaseConnectionProfile.class);
        verify(this.databaseConnectionProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
    }
}
