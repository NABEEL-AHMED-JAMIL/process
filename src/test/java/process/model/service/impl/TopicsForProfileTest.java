package process.model.service.impl;

import org.barco.platform.tenancy.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import java.util.List;
import java.util.Arrays;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import process.model.projection.TopicOptionProjection;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The Kafka pane asks for one profile's topics at a time. The profile has to be the caller's
 * to see, and a default profile also carries the workspace's unrouted topics.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class TopicsForProfileTest {

    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository kafkaConnectionProfileRepository;
    @Mock private TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private UserNameResolver userNameResolver;

    private SettingServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SettingServiceImpl(this.sourceJobRepository,
            this.sourceTaskTypeRepository, this.kafkaConnectionProfileRepository,
            this.tenantTaskTypeKafkaRouteRepository, null, this.kafkaTemplateProvider,
            this.kafkaConnectionResolver, this.userNameResolver);
        TenantContext.set(MINE, "TENANT_ADMIN", 4385L, "emily@example.com");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private static KafkaConnectionProfile profile(long id, Long tenantId, boolean isDefault) {
        KafkaConnectionProfile p = new KafkaConnectionProfile();
        p.setKafkaConnectionProfileId(id);
        p.setTenantId(tenantId);
        p.setProfileName("Broker " + id);
        p.setStatus(Status.Active);
        p.setIsDefault(isDefault);
        return p;
    }

    @Test
    void anotherWorkspacesProfileReadsAsNotFound() throws Exception {
        when(this.kafkaConnectionProfileRepository.findById(7L)).thenReturn(Optional.of(profile(7L, THEIRS, true)));

        ResponseDto response = this.service.topicsForProfile(7L);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("Profile not found");
        verify(this.sourceTaskTypeRepository, never()).fetchTopicsForProfile(anyLong(), anyBoolean(), any());
    }

    @Test
    void aDefaultProfileAlsoCarriesTheWorkspacesUnroutedTopics() throws Exception {
        when(this.kafkaConnectionProfileRepository.findById(8L)).thenReturn(Optional.of(profile(8L, MINE, true)));
        when(this.sourceTaskTypeRepository.fetchTopicsForProfile(8L, true, MINE)).thenReturn(Collections.emptyList());

        ResponseDto response = this.service.topicsForProfile(8L);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskTypeRepository).fetchTopicsForProfile(eq(8L), eq(true), eq(MINE));
    }

    @Test
    void aNonDefaultProfileCarriesOnlyTheTopicsThatNameIt() throws Exception {
        when(this.kafkaConnectionProfileRepository.findById(9L)).thenReturn(Optional.of(profile(9L, MINE, false)));
        when(this.sourceTaskTypeRepository.fetchTopicsForProfile(9L, false, MINE)).thenReturn(Collections.emptyList());

        this.service.topicsForProfile(9L);

        verify(this.sourceTaskTypeRepository).fetchTopicsForProfile(eq(9L), eq(false), eq(MINE));
    }

    // ---- topics(): the picker rows, three ways in, each scoped ---------------------------------

    private static TopicOptionProjection option(long id, Long tenantId) {
        // Only the tenant is read by the filter; lenient so an unread id is not an error.
        TopicOptionProjection t = mock(TopicOptionProjection.class);
        lenient().when(t.getSourceTaskTypeId()).thenReturn(id);
        when(t.getTenantId()).thenReturn(tenantId);
        return t;
    }

    @Test
    void aSearchIsScopedToTheWorkspaceAndCapped() throws Exception {
        when(this.sourceTaskTypeRepository.searchTopicOptions(eq(false), eq(MINE), eq("%claims%"), any())).thenReturn(Collections.emptyList());

        ResponseDto response = this.service.topics(" Claims ", 20, null, null);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
        verify(this.sourceTaskTypeRepository).searchTopicOptions(eq(false), eq(MINE), eq("%claims%"), window.capture());
        assertThat(window.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void aPlatformAdminSearchesEveryWorkspace() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.sourceTaskTypeRepository.searchTopicOptions(eq(true), eq(TenantScope.NO_TENANT_MATCHES), eq(""), any())).thenReturn(Collections.emptyList());

        this.service.topics(null, null, null, null);

        ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
        verify(this.sourceTaskTypeRepository).searchTopicOptions(eq(true), eq(TenantScope.NO_TENANT_MATCHES), eq(""), window.capture());
        assertThat(window.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void resolvingIdsDropsAnotherWorkspacesRows() throws Exception {
        TopicOptionProjection mine = option(11L, MINE);
        TopicOptionProjection theirs = option(12L, THEIRS);
        when(this.sourceTaskTypeRepository.fetchTopicOptionsByIds(Arrays.asList(11L, 12L))).thenReturn(Arrays.asList(mine, theirs));

        ResponseDto response = this.service.topics(null, null, Arrays.asList(11L, 12L), null);

        assertThat((List<Object>) response.getData()).containsExactly(mine);
    }

    @Test
    void aProfilesTopicsReadAsNotFoundForAnotherWorkspacesProfile() throws Exception {
        when(this.kafkaConnectionProfileRepository.findById(7L)).thenReturn(Optional.of(profile(7L, THEIRS, true)));

        ResponseDto response = this.service.topics(null, null, null, 7L);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceTaskTypeRepository, never()).fetchTopicOptionsForProfile(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void aDefaultProfilesPickerRowsCarryTheUnroutedTopicsToo() throws Exception {
        when(this.kafkaConnectionProfileRepository.findById(8L)).thenReturn(Optional.of(profile(8L, MINE, true)));
        when(this.sourceTaskTypeRepository.fetchTopicOptionsForProfile(8L, true, MINE)).thenReturn(Collections.emptyList());

        this.service.topics(null, null, null, 8L);

        verify(this.sourceTaskTypeRepository).fetchTopicOptionsForProfile(eq(8L), eq(true), eq(MINE));
    }

    // ---- the platform default: what a workspace with no Kafka of its own publishes through ----

    private static final long PLATFORM_DEFAULT = 1009L;

    private void platformDefaultIs(long id) {
        when(this.kafkaConnectionProfileRepository.findById(id)).thenReturn(Optional.of(profile(id, null, true)));
    }

    @Test
    void aWorkspaceWithNoKafkaReadsThePlatformDefaultsTopicsInItsOwnWorkspace() throws Exception {
        this.platformDefaultIs(PLATFORM_DEFAULT);
        when(this.kafkaConnectionProfileRepository.countByTenantIdAndStatusNot(MINE, Status.Delete)).thenReturn(0L);
        when(this.sourceTaskTypeRepository.fetchTopicsForPlatformDefault(PLATFORM_DEFAULT, false, MINE)).thenReturn(Collections.emptyList());

        ResponseDto response = this.service.topicsForProfile(PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskTypeRepository).fetchTopicsForPlatformDefault(eq(PLATFORM_DEFAULT), eq(false), eq(MINE));
        verify(this.sourceTaskTypeRepository, never()).fetchTopicsForProfile(anyLong(), anyBoolean(), any());
    }

    @Test
    void aWorkspaceWithKafkaOfItsOwnCannotReadThePlatformDefault() throws Exception {
        this.platformDefaultIs(PLATFORM_DEFAULT);
        when(this.kafkaConnectionProfileRepository.countByTenantIdAndStatusNot(MINE, Status.Delete)).thenReturn(1L);

        ResponseDto response = this.service.topicsForProfile(PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("Profile not found");
        verify(this.sourceTaskTypeRepository, never()).fetchTopicsForPlatformDefault(any(), anyBoolean(), any());
    }

    @Test
    void aPlatformProfileThatIsNotTheDefaultStaysHiddenFromATenant() throws Exception {
        when(this.kafkaConnectionProfileRepository.findById(1010L)).thenReturn(Optional.of(profile(1010L, null, false)));

        ResponseDto response = this.service.topicsForProfile(1010L);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceTaskTypeRepository, never()).fetchTopicsForProfile(anyLong(), anyBoolean(), any());
        verify(this.sourceTaskTypeRepository, never()).fetchTopicsForPlatformDefault(any(), anyBoolean(), any());
    }

    @Test
    void aPlatformAdminReadsThePlatformDefaultAcrossEveryWorkspaceWithNoKafka() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        this.platformDefaultIs(PLATFORM_DEFAULT);
        when(this.sourceTaskTypeRepository.fetchTopicsForPlatformDefault(PLATFORM_DEFAULT, true, null)).thenReturn(Collections.emptyList());

        this.service.topicsForProfile(PLATFORM_DEFAULT);

        verify(this.sourceTaskTypeRepository).fetchTopicsForPlatformDefault(eq(PLATFORM_DEFAULT), eq(true), eq(null));
    }

    @Test
    void aPlatformAdminsOtherPlatformProfilesCarryOnlyTheTopicsThatNameThem() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.kafkaConnectionProfileRepository.findById(1010L)).thenReturn(Optional.of(profile(1010L, null, false)));
        when(this.sourceTaskTypeRepository.fetchTopicsForProfile(1010L, false, null)).thenReturn(Collections.emptyList());

        this.service.topicsForProfile(1010L);

        verify(this.sourceTaskTypeRepository).fetchTopicsForProfile(eq(1010L), eq(false), eq(null));
    }

    @Test
    void thePickerOffersThePlatformDefaultsTopicsToAWorkspaceWithNoKafka() throws Exception {
        this.platformDefaultIs(PLATFORM_DEFAULT);
        when(this.kafkaConnectionProfileRepository.countByTenantIdAndStatusNot(MINE, Status.Delete)).thenReturn(0L);
        when(this.sourceTaskTypeRepository.fetchTopicOptionsForPlatformDefault(PLATFORM_DEFAULT, false, MINE)).thenReturn(Collections.emptyList());

        ResponseDto response = this.service.topics(null, null, null, PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceTaskTypeRepository).fetchTopicOptionsForPlatformDefault(eq(PLATFORM_DEFAULT), eq(false), eq(MINE));
        verify(this.sourceTaskTypeRepository, never()).fetchTopicOptionsForProfile(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void thePickerRefusesThePlatformDefaultToAWorkspaceWithKafkaOfItsOwn() throws Exception {
        this.platformDefaultIs(PLATFORM_DEFAULT);
        when(this.kafkaConnectionProfileRepository.countByTenantIdAndStatusNot(MINE, Status.Delete)).thenReturn(2L);

        ResponseDto response = this.service.topics(null, null, null, PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceTaskTypeRepository, never()).fetchTopicOptionsForPlatformDefault(anyLong(), anyBoolean(), any());
    }

    @Test
    void aPlatformAdminsPickerOnThePlatformDefaultSpansEveryWorkspace() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        this.platformDefaultIs(PLATFORM_DEFAULT);
        when(this.sourceTaskTypeRepository.fetchTopicOptionsForPlatformDefault(PLATFORM_DEFAULT, true, null)).thenReturn(Collections.emptyList());

        this.service.topics(null, null, null, PLATFORM_DEFAULT);

        verify(this.sourceTaskTypeRepository).fetchTopicOptionsForPlatformDefault(eq(PLATFORM_DEFAULT), eq(true), eq(null));
    }
}
