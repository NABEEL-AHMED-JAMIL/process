package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.config.StorageClientFactory;
import process.model.dto.ResponseDto;
import process.model.dto.StorageConnectionDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A caller carrying no tenant of its own against the platform's own connections.
 *
 * The platform's rows are the ones with no tenant -- etl-avatar, etl-bucket, and anything else an
 * operator adds -- and ownership used to be a bare comparison of the two tenant ids, which says
 * yes when both are absent. Any principal that is not a platform admin and reaches the server
 * without a tenant claim was therefore the owner of every one of them: it could list them, clone
 * them, retire them, and repoint their endpoint and credentials at storage of its own, after which
 * every profile picture and every Kafka certificate the platform writes lands there.
 *
 * The alias is deliberately not one of the two reserved names: those are refused to a
 * non-platform-admin by a separate rule, and a fixture that leaned on it would report this guard
 * as working when it had been deleted. aPlatformAdminStillManagesTheSameRow is the other half of
 * that -- the same fixture, admitted -- so a refusal here cannot be the fixture failing to resolve.
 *
 * @author Nabeel Ahmed
 */
class StorageConnectionTenantlessCallerTest {

    private static final long CONNECTION_ID = 55L;
    private static final String PLATFORM_ALIAS = "platform-archive";

    private final StorageConnectionRepository storageConnectionRepository =
        mock(StorageConnectionRepository.class);
    private final StorageClientFactory storageClientFactory = mock(StorageClientFactory.class);
    private final KafkaConnectionProfileRepository profileRepository =
        mock(KafkaConnectionProfileRepository.class);
    private final EncryptionUtil encryptionUtil = mock(EncryptionUtil.class);
    private final TenantFilterHelper tenantFilterHelper = mock(TenantFilterHelper.class);
    private final UserNameResolver userNameResolver = mock(UserNameResolver.class);

    private StorageConnectionServiceImpl service;
    private StorageConnection platformConnection;

    @BeforeEach
    void setUp() {
        this.service = new StorageConnectionServiceImpl(this.storageConnectionRepository,
            this.storageClientFactory, this.profileRepository, this.encryptionUtil,
            this.tenantFilterHelper, this.userNameResolver);
        this.platformConnection = new StorageConnection();
        this.platformConnection.setStorageConnectionId(CONNECTION_ID);
        this.platformConnection.setTenantId(null);
        this.platformConnection.setAlias(PLATFORM_ALIAS);
        this.platformConnection.setConnectionName("Platform archive");
        this.platformConnection.setProvider(StorageProvider.MINIO);
        this.platformConnection.setBucketName(PLATFORM_ALIAS);
        this.platformConnection.setEndpoint("http://minio:9000");
        this.platformConnection.setStatus(Status.Active);
        when(this.storageConnectionRepository.findById(CONNECTION_ID))
            .thenReturn(Optional.of(this.platformConnection));
        when(this.storageConnectionRepository.findByAlias(PLATFORM_ALIAS))
            .thenReturn(Optional.of(this.platformConnection));
        when(this.storageConnectionRepository.findByStatusNotOrderByStorageConnectionIdDesc(Status.Delete))
            .thenReturn(Collections.singletonList(this.platformConnection));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A role that is not the platform admin's, and no tenant behind it. */
    private void actAsTenantlessCaller() {
        TenantContext.set(null, "TENANT_ADMIN", 9000L, "no-tenant@example.com");
    }

    /** An edit that changes nothing but has to get past ownership to be applied. */
    private StorageConnectionDto repointedAtSomewhereElse() {
        StorageConnectionDto dto = new StorageConnectionDto();
        dto.setStorageConnectionId(CONNECTION_ID);
        dto.setConnectionName("Platform archive");
        dto.setAlias(PLATFORM_ALIAS);
        dto.setProvider(StorageProvider.MINIO);
        dto.setBucketName(PLATFORM_ALIAS);
        dto.setEndpoint("http://attacker-controlled:9000");
        return dto;
    }

    @Test
    void aTenantlessCallerCannotRepointAPlatformConnection() throws Exception {
        this.actAsTenantlessCaller();

        ResponseDto response = this.service.updateConnection(this.repointedAtSomewhereElse());

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("not found");
        assertThat(this.platformConnection.getEndpoint()).isEqualTo("http://minio:9000");
        verify(this.storageConnectionRepository, never()).save(any(StorageConnection.class));
    }

    @Test
    void norRetireIt() throws Exception {
        this.actAsTenantlessCaller();

        ResponseDto response = this.service.deleteConnection(CONNECTION_ID);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(this.platformConnection.getStatus()).isEqualTo(Status.Active);
        verify(this.storageConnectionRepository, never()).save(any(StorageConnection.class));
    }

    @Test
    void norReadItBackById() throws Exception {
        this.actAsTenantlessCaller();

        ResponseDto response = this.service.fetchConnectionById(CONNECTION_ID);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getData()).isNull();
    }

    @Test
    void norSeeItOnTheStorageScreen() throws Exception {
        this.actAsTenantlessCaller();

        ResponseDto response = this.service.fetchAllConnections();

        assertThat((List<?>) response.getData()).isEmpty();
    }

    /**
     * The control: the same row, the same stubs, a caller the rule does admit. Without this every
     * refusal above could equally be a fixture that resolves nothing at all.
     */
    @Test
    void aPlatformAdminStillManagesTheSameRow() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        ResponseDto response = this.service.fetchConnectionById(CONNECTION_ID);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isNotNull();
        assertThat((List<?>) this.service.fetchAllConnections().getData()).hasSize(1);
    }

}
