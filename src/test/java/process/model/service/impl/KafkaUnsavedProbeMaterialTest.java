package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.storage.remote.RemoteStorageDirectory;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-214: Test Connection on a profile that has never been saved downloads its stores into a directory of its own
 * (there is no id to cache under), and only deleteOnExit was ever asked to remove it: each click left key material on
 * disk and two entries in the JVM's never-pruned DeleteOnExitHook set until the process ended. The probe hands its
 * client properties back to the provider to discard once the client is closed, pass or fail.
 */
@ExtendWith(MockitoExtension.class)
public class KafkaUnsavedProbeMaterialTest {

    @Mock private KafkaConnectionProfileRepository profileRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private TenantTaskTypeKafkaRouteRepository routeRepository;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private UserNameResolver userNameResolver;
    @Mock private KafkaSecretService kafkaSecretService;
    @Mock private RemoteStorageDirectory storageDirectory;

    private KafkaConnectionProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new KafkaConnectionProfileServiceImpl(this.profileRepository,
            this.sourceTaskTypeRepository, this.routeRepository, this.encryptionUtil,
            this.kafkaTemplateProvider, this.kafkaConnectionResolver, this.userNameResolver,
            this.kafkaSecretService, this.storageDirectory);
        TenantContext.set(1001L, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anUnsavedProfilesProbeDiscardsWhatItDownloaded() throws Exception {
        // Empty, so AdminClient.create fails on its own configuration instead of opening a socket: the failing
        // probe is the case that must still clean up.
        Map<String, Object> props = new HashMap<>();
        when(this.kafkaTemplateProvider.commonClientProps(any())).thenReturn(props);
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("new-profile");
        dto.setBootstrapServers("broker:9092");
        dto.setSecurityProtocol("PLAINTEXT");

        this.service.testConnection(dto);

        verify(this.kafkaTemplateProvider).discardUnsaved(same(props));
    }
}
