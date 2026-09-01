package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.sql.Connection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a connection test is allowed to do to the profile it tests.
 *
 * The probe used to run against the row loaded from the database, which is a managed entity --
 * writing the caller's host onto it repointed the saved profile for good the moment the
 * transaction committed, and the credentials it dialled with were the ones already stored. So
 * two things are checked here: the stored row never changes, and only the role that may edit a
 * profile may test it against a host or password other than its own.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class ConnectionProfileTestConnectionSafetyTest {

    private static final long TENANT_A = 1001L;

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
    @Mock
    private Connection connection;

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

    private DatabaseConnectionProfile savedProfile() {
        DatabaseConnectionProfile profile = new DatabaseConnectionProfile();
        profile.setDatabaseConnectionProfileId(500L);
        profile.setTenantId(TENANT_A);
        profile.setProfileName("Production");
        profile.setDatabaseType(DatabaseType.POSTGRES);
        profile.setHost("prod-db.internal");
        profile.setPort(5432);
        profile.setDatabaseName("prod");
        profile.setUsername("prod_user");
        profile.setPasswordEncrypted("stored-cipher");
        profile.setStatus(Status.Active);
        return profile;
    }

    private DatabaseConnectionProfileDto testRequestFor(long profileId) {
        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setDatabaseConnectionProfileId(profileId);
        return dto;
    }

    private void actAs(String userRole) {
        TenantContext.set(TENANT_A, userRole, 9000L, "someone@example.com");
    }

    @Test
    void aTenantUserCannotPointASavedProfileAtAnotherHost() throws Exception {
        DatabaseConnectionProfile stored = this.savedProfile();
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(stored));

        DatabaseConnectionProfileDto dto = this.testRequestFor(500L);
        dto.setHost("evil.attacker.net");

        this.actAs("TENANT_USER");
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionFactory, never()).openConnection(any());
        assertThat(stored.getHost()).isEqualTo("prod-db.internal");
    }

    @Test
    void aTenantUserCannotOverwriteAStoredPasswordThroughATest() throws Exception {
        DatabaseConnectionProfile stored = this.savedProfile();
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(stored));

        DatabaseConnectionProfileDto dto = this.testRequestFor(500L);
        dto.setPassword("whatever");

        this.actAs("TENANT_USER");
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionFactory, never()).openConnection(any());
        assertThat(stored.getPasswordEncrypted()).isEqualTo("stored-cipher");
    }

    @Test
    void aTenantUserCannotTestAProfileThatWasNeverSaved() throws Exception {
        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setProfileName("scratch");
        dto.setDatabaseType(DatabaseType.POSTGRES);
        dto.setHost("10.0.4.17");
        dto.setPort(5432);
        dto.setDatabaseName("db");
        dto.setUsername("user");
        dto.setPassword("pw");

        this.actAs("TENANT_USER");
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isNotEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.databaseConnectionFactory, never()).openConnection(any());
    }

    @Test
    void aTenantUserMayStillTestASavedProfileAsItStands() throws Exception {
        DatabaseConnectionProfile stored = this.savedProfile();
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(stored));
        when(this.databaseConnectionFactory.openConnection(any())).thenReturn(this.connection);
        when(this.connection.isValid(anyInt())).thenReturn(true);

        this.actAs("TENANT_USER");
        ResponseDto response = this.service.testConnection(this.testRequestFor(500L));

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void anAdminsOverrideIsProbedOnACopyAndNeverWrittenBackToTheStoredRow() throws Exception {
        DatabaseConnectionProfile stored = this.savedProfile();
        when(this.databaseConnectionProfileRepository.findById(500L)).thenReturn(Optional.of(stored));
        when(this.encryptionUtil.encrypt("new-password")).thenReturn("new-cipher");
        when(this.databaseConnectionFactory.openConnection(any())).thenReturn(this.connection);
        when(this.connection.isValid(anyInt())).thenReturn(true);

        DatabaseConnectionProfileDto dto = this.testRequestFor(500L);
        dto.setHost("staging-db.internal");
        dto.setPassword("new-password");

        this.actAs("TENANT_ADMIN");
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);

        ArgumentCaptor<DatabaseConnectionProfile> captor =
            ArgumentCaptor.forClass(DatabaseConnectionProfile.class);
        verify(this.databaseConnectionFactory).openConnection(captor.capture());
        assertThat(captor.getValue()).isNotSameAs(stored);
        assertThat(captor.getValue().getHost()).isEqualTo("staging-db.internal");
        assertThat(captor.getValue().getPasswordEncrypted()).isEqualTo("new-cipher");

        // The loaded row is managed, so anything written onto it would be flushed at commit.
        assertThat(stored.getHost()).isEqualTo("prod-db.internal");
        assertThat(stored.getPasswordEncrypted()).isEqualTo("stored-cipher");
        verify(this.databaseConnectionProfileRepository, never()).save(any());
    }
}
