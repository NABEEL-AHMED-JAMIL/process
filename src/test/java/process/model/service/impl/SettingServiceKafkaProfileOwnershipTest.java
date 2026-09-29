package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Which Kafka connection a topic or a route may name (MIG-166 review).
 *
 * The check accepted any profile with no tenant and any profile at all whatever its status, so a
 * workspace could bind its topic to a deleted connection -- its own, or the platform's -- and a
 * platform administrator could too. A deleted profile is gone: nobody may name it, and the answer
 * is word for word the one another workspace's profile gets, so the refusal does not tell a
 * caller which ids exist.
 *
 * The platform's own profiles (tenant_id NULL) stay usable by every workspace while they are live:
 * that is deliberate -- the resolver shares them, fetchAllProfiles shows the platform default to a
 * workspace without Kafka of its own, and CoreCrossTenantProbeSettingsAndKafkaPostgresTest pins
 * "any workspace may USE it". A caller with no workspace owns nothing and uses nothing.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SettingServiceKafkaProfileOwnershipTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    private static final long A_TYPE = 11L;

    private static final long A_LIVE = 101L;
    private static final long A_INACTIVE = 102L;
    private static final long A_DELETED = 103L;
    private static final long B_LIVE = 201L;
    private static final long B_DELETED = 202L;
    private static final long PLATFORM_LIVE = 301L;
    private static final long PLATFORM_DELETED = 302L;
    private static final long NO_SUCH_PROFILE = 999L;

    private static final String NOT_FOUND = "Kafka connection profile not found.";

    @Mock
    private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock
    private KafkaConnectionProfileRepository profileRepository;
    @Mock
    private TenantTaskTypeKafkaRouteRepository routeRepository;

    private SettingServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SettingServiceImpl(null, this.sourceTaskTypeRepository, this.profileRepository,
            this.routeRepository, null, null, null, null);
        stubProfile(A_LIVE, TENANT_A, Status.Active);
        stubProfile(A_INACTIVE, TENANT_A, Status.Inactive);
        stubProfile(A_DELETED, TENANT_A, Status.Delete);
        stubProfile(B_LIVE, TENANT_B, Status.Active);
        stubProfile(B_DELETED, TENANT_B, Status.Delete);
        stubProfile(PLATFORM_LIVE, null, Status.Active);
        stubProfile(PLATFORM_DELETED, null, Status.Delete);
        when(this.profileRepository.findById(NO_SUCH_PROFILE)).thenReturn(Optional.empty());

        SourceTaskType aType = new SourceTaskType();
        aType.setSourceTaskTypeId(A_TYPE);
        aType.setTenantId(TENANT_A);
        when(this.sourceTaskTypeRepository.findById(A_TYPE)).thenReturn(Optional.of(aType));
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void stubProfile(long id, Long tenantId, Status status) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(id);
        profile.setTenantId(tenantId);
        profile.setStatus(status);
        profile.setIsDefault(Boolean.TRUE);
        when(this.profileRepository.findById(id)).thenReturn(Optional.of(profile));
    }

    private static void callerIsAdminOfA() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "a@example.com");
    }

    private ResponseDto routeATypeThrough(long profileId) throws Exception {
        return this.service.setKafkaRoute(A_TYPE, profileId);
    }

    private static SourceTaskTypeDto topic(Long profileId) {
        SourceTaskTypeDto dto = new SourceTaskTypeDto();
        dto.setServiceName("Acme Topic");
        dto.setQueueTopicPartition("topic=acme-topic&partitions=[*]");
        dto.setKafkaConnectionProfileId(profileId);
        return dto;
    }

    // ---- the happy path

    @Test
    void aWorkspaceMayRouteThroughItsOwnLiveProfile() throws Exception {
        callerIsAdminOfA();

        ResponseDto response = routeATypeThrough(A_LIVE);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        verify(this.routeRepository).save(any());
    }

    @Test
    void anInactiveProfileOfItsOwnIsStillItsOwn() throws Exception {
        // Inactive is paused, not gone: the resolver still counts it as "Kafka of its own".
        callerIsAdminOfA();

        assertThat(routeATypeThrough(A_INACTIVE).getStatus()).isEqualTo(SUCCESS);
    }

    @Test
    void aLivePlatformProfileIsSharedWithEveryWorkspace() throws Exception {
        callerIsAdminOfA();

        assertThat(routeATypeThrough(PLATFORM_LIVE).getStatus()).isEqualTo(SUCCESS);
    }

    // ---- cross-tenant

    @Test
    void anotherWorkspacesLiveProfileIsRefusedAsNotFound() throws Exception {
        callerIsAdminOfA();

        ResponseDto response = routeATypeThrough(B_LIVE);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo(NOT_FOUND);
        verify(this.routeRepository, never()).save(any());
    }

    @Test
    void anotherWorkspacesProfileCannotBeTheDefaultOfANewTopic() throws Exception {
        callerIsAdminOfA();

        ResponseDto response = this.service.addSourceTaskType(topic(B_LIVE));

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo(NOT_FOUND);
        verify(this.sourceTaskTypeRepository, never()).save(any());
    }

    @Test
    void anotherWorkspacesDeletedProfileReadsTheSameAsOneThatNeverExisted() throws Exception {
        callerIsAdminOfA();

        assertThat(routeATypeThrough(B_DELETED).getMessage()).isEqualTo(NOT_FOUND);
        assertThat(routeATypeThrough(NO_SUCH_PROFILE).getMessage()).isEqualTo(NOT_FOUND);
        verify(this.routeRepository, never()).save(any());
    }

    // ---- deleted

    @Test
    void aWorkspacesOwnDeletedProfileIsRefusedExactlyAsAForeignOneIs() throws Exception {
        callerIsAdminOfA();

        ResponseDto own = routeATypeThrough(A_DELETED);
        ResponseDto foreign = routeATypeThrough(B_LIVE);

        assertThat(own.getStatus()).isEqualTo(ERROR);
        assertThat(own.getMessage()).isEqualTo(foreign.getMessage()).isEqualTo(NOT_FOUND);
        assertThat(this.service.addSourceTaskType(topic(A_DELETED)).getMessage()).isEqualTo(NOT_FOUND);
        verify(this.routeRepository, never()).save(any());
        verify(this.sourceTaskTypeRepository, never()).save(any());
    }

    @Test
    void aDeletedPlatformProfileIsRefused() throws Exception {
        callerIsAdminOfA();

        ResponseDto response = routeATypeThrough(PLATFORM_DELETED);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo(NOT_FOUND);
        verify(this.routeRepository, never()).save(any());
    }

    @Test
    void aPlatformAdministratorCannotNameADeletedProfileEither() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        for (long deleted : new long[] {A_DELETED, B_DELETED, PLATFORM_DELETED}) {
            SourceTaskTypeDto dto = topic(deleted);
            dto.setTenantId(TENANT_A);
            ResponseDto response = this.service.addSourceTaskType(dto);
            assertThat(response.getStatus()).isEqualTo(ERROR);
            assertThat(response.getMessage()).isEqualTo(NOT_FOUND);
        }
        verify(this.sourceTaskTypeRepository, never()).save(any());
    }

    @Test
    void aPlatformAdministratorMayStillNameAnyLiveProfile() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        // Past the profile check: the next refusal is the missing workspace, not the profile.
        for (long live : new long[] {A_LIVE, B_LIVE, PLATFORM_LIVE}) {
            assertThat(this.service.addSourceTaskType(topic(live)).getMessage()).startsWith("Topic workspace missing");
        }
    }

    // ---- a caller with no workspace

    @Test
    void aCallerWithNoWorkspaceMayNameNoProfileAtAll() throws Exception {
        TenantContext.set(null, "TENANT_ADMIN", 1L, "nobody@example.com");

        assertThat(this.service.addSourceTaskType(topic(PLATFORM_LIVE)).getMessage()).isEqualTo(NOT_FOUND);
        assertThat(this.service.addSourceTaskType(topic(A_LIVE)).getMessage()).isEqualTo(NOT_FOUND);
        verify(this.sourceTaskTypeRepository, never()).save(any());
    }
}
