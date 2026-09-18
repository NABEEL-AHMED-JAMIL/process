package process.model.service.impl;

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
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
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

    @Mock private LookupDataRepository lookupDataRepository;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository kafkaConnectionProfileRepository;
    @Mock private TenantTaskTypeKafkaRouteRepository tenantTaskTypeKafkaRouteRepository;
    @Mock private EncryptionUtil encryptionUtil;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private LookupDataCacheService lookupDataCacheService;
    @Mock private UserNameResolver userNameResolver;

    private SettingServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SettingServiceImpl(this.lookupDataRepository, this.sourceJobRepository,
            this.sourceTaskTypeRepository, this.kafkaConnectionProfileRepository,
            this.tenantTaskTypeKafkaRouteRepository, null, this.encryptionUtil, this.kafkaTemplateProvider,
            this.kafkaConnectionResolver, this.lookupDataCacheService, this.userNameResolver);
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
}
