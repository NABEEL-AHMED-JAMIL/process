package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which references actually stand in the way of deleting a Kafka connection profile.
 *
 * A task type is never removed from source_task_type: deleteSourceTaskType sets
 * task_type_status = Delete and leaves kafka_connection_profile_id where it was. The delete guard
 * asked existsByKafkaConnectionProfileId, which does not look at that status, so a profile whose
 * last task type had been deleted stayed referenced for good -- the operator was told to reassign
 * the task types first, and the task types they were sent to reassign no longer appear anywhere.
 * The guard now asks about task types that still exist.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class KafkaProfileDeleteGuardTest {

    private static final long TENANT_A = 1001L;
    private static final long PROFILE_ID = 700L;

    @Mock
    private KafkaConnectionProfileRepository profileRepository;
    @Mock
    private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock
    private TenantTaskTypeKafkaRouteRepository routeRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock
    private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock
    private UserNameResolver userNameResolver;
    @Mock
    private KafkaSecretService kafkaSecretService;
    @Mock
    private StorageConnectionRepository storageConnectionRepository;

    private KafkaConnectionProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new KafkaConnectionProfileServiceImpl(this.profileRepository,
            this.sourceTaskTypeRepository, this.routeRepository, this.encryptionUtil,
            this.kafkaTemplateProvider, this.kafkaConnectionResolver, this.userNameResolver,
            this.kafkaSecretService, this.storageConnectionRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * The regression: the only task types that ever pointed at this profile have been deleted, so
     * nothing reachable uses it and it has to be deletable.
     */
    @Test
    void aProfileOnlyDeletedTaskTypesEverUsedCanStillBeDeleted() throws Exception {
        when(this.profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        // What the fixed guard asks: task types other than the deleted ones. There are none.
        when(this.sourceTaskTypeRepository.existsByKafkaConnectionProfileIdAndStatusNot(PROFILE_ID, Status.Delete))
            .thenReturn(false);
        when(this.routeRepository.existsByKafkaConnectionProfileId(PROFILE_ID)).thenReturn(false);

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.deleteProfile(PROFILE_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.profileRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Status.Delete);
    }

    /** A task type that is still there is still a reason to refuse, and a business one -- so 200. */
    @Test
    void aLiveTaskTypeStillBlocksTheDelete() throws Exception {
        when(this.profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        when(this.sourceTaskTypeRepository.existsByKafkaConnectionProfileIdAndStatusNot(PROFILE_ID, Status.Delete))
            .thenReturn(true);

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.deleteProfile(PROFILE_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("Source Task Type");
        verify(this.profileRepository, never()).save(any());
    }

    /** Routes carry no status of their own -- they are removed outright -- so any row still blocks. */
    @Test
    void aTenantRoutingOverrideStillBlocksTheDelete() throws Exception {
        when(this.profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        when(this.sourceTaskTypeRepository.existsByKafkaConnectionProfileIdAndStatusNot(PROFILE_ID, Status.Delete))
            .thenReturn(false);
        when(this.routeRepository.existsByKafkaConnectionProfileId(PROFILE_ID)).thenReturn(true);

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.deleteProfile(PROFILE_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("routing override");
        verify(this.profileRepository, never()).save(any());
    }

    private KafkaConnectionProfile profileOwnedBy(Long tenantId) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(PROFILE_ID);
        profile.setTenantId(tenantId);
        profile.setProfileName("retired-broker");
        profile.setBootstrapServers("stored-broker:9092");
        profile.setSecurityProtocol("PLAINTEXT");
        profile.setStatus(Status.Active);
        return profile;
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
    }

}
