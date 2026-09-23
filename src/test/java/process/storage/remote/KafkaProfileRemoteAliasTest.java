package process.storage.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.ResponseDto;
import process.model.pojo.StorageConnection;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.model.service.impl.KafkaConnectionProfileServiceImpl;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A Kafka profile may name a storage connection for its key material; once Storage owns the
 * connections the name is checked against storage-service's directory (MIG-68). A workspace may name
 * only its own; a platform administrator any; nobody a name nobody has.
 */
class KafkaProfileRemoteAliasTest {

    private static final long ACME = 2901L;
    private static final long GLOBEX = 2902L;

    private final StorageConnectionRepository table = mock(StorageConnectionRepository.class);
    private final RemoteStorageDirectory remote = mock(RemoteStorageDirectory.class);
    private final KafkaSecretService secrets = mock(KafkaSecretService.class);
    private KafkaConnectionProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new KafkaConnectionProfileServiceImpl(mock(KafkaConnectionProfileRepository.class),
            mock(SourceTaskTypeRepository.class), mock(TenantTaskTypeKafkaRouteRepository.class), mock(EncryptionUtil.class),
            mock(KafkaTemplateProvider.class), mock(KafkaConnectionResolver.class), mock(UserNameResolver.class),
            this.secrets, this.table);
        ReflectionTestUtils.setField(this.service, "remote", this.remote);
        when(this.secrets.secretBucket()).thenReturn("etl-config");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static StorageConnection owned(Long tenantId) {
        StorageConnection c = new StorageConnection();
        c.setTenantId(tenantId);
        c.setAlias("certs");
        return c;
    }

    private ResponseDto refusal() {
        return ReflectionTestUtils.invokeMethod(this.service, "refuseUnusableSecret", "certs", "kafka/truststore.p12",
            null, null, "truststore");
    }

    @Test
    void aWorkspaceMayNameItsOwnConnection() {
        TenantContext.set(ACME, "TENANT_ADMIN", 61L, "admin@acme.example");
        when(this.remote.byAlias("certs")).thenReturn(Arrays.asList(owned(GLOBEX), owned(ACME)));

        assertThat(this.refusal()).isNull();
        verifyNoInteractions(this.table);
    }

    @Test
    void butNotAnotherWorkspacesNorThePlatformsByTheSameName() {
        TenantContext.set(ACME, "TENANT_ADMIN", 61L, "admin@acme.example");
        when(this.remote.byAlias("certs")).thenReturn(Arrays.asList(owned(GLOBEX), owned(null)));

        assertThat(this.refusal().getMessage()).isEqualTo("No storage connection is called 'certs'.");
        verifyNoInteractions(this.table);
    }

    @Test
    void aPlatformAdministratorMayNameAnyButNotOneNobodyHas() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        when(this.remote.byAlias("certs")).thenReturn(Collections.singletonList(owned(GLOBEX)));
        assertThat(this.refusal()).isNull();

        when(this.remote.byAlias("certs")).thenReturn(Collections.emptyList());
        assertThat(this.refusal().getMessage()).isEqualTo("No storage connection is called 'certs'.");
        verifyNoInteractions(this.table);
    }
}
