package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.AuditNamed;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.storage.remote.RemoteStorageDirectory;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A workspace with no Kafka profile of its own sends its runs through the platform default
 * (KafkaConnectionResolver tier 4, MIG-45), and its admin is now shown that connection -- by name,
 * read-only, with nothing that says where the brokers are or how to log in to them. The task
 * editor's "Kafka connection" box came up blank because the list it picks from was empty.
 *
 * "Of its own" is the resolver's reading: any profile not deleted, inactive included. A workspace
 * that has brought its own brokers is refused rather than put on the platform's, so it is not
 * shown the platform's either.
 */
@ExtendWith(MockitoExtension.class)
public class KafkaPlatformDefaultVisibilityTest {

    private static final long NO_KAFKA = 2924L;
    private static final long OWN_KAFKA = 2905L;
    private static final long PLATFORM_DEFAULT = 1009L;

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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static KafkaConnectionProfile platformDefault() {
        KafkaConnectionProfile p = new KafkaConnectionProfile();
        p.setKafkaConnectionProfileId(PLATFORM_DEFAULT);
        p.setTenantId(null);
        p.setProfileName("Platform Local Broker [PF]");
        p.setEnvironmentLabel("local");
        p.setBootstrapServers("platform-kafka.internal:9092");
        p.setSecurityProtocol("SASL_SSL");
        p.setSaslMechanism("SCRAM-SHA-512");
        p.setSaslUsername("platform-etl");
        p.setSaslPassword("stored-cipher");
        p.setSslKeystoreBucket("etl-bucket");
        p.setSslKeystoreLocation("kafka-secrets/1/keystore.p12");
        p.setSslKeystorePasswordEnc("stored-cipher");
        p.setSslKeyPasswordEnc("stored-cipher");
        p.setSslTruststoreBucket("etl-bucket");
        p.setSslTruststoreLocation("kafka-secrets/1/truststore.p12");
        p.setSslTruststorePasswordEnc("stored-cipher");
        p.setAdditionalProperties("{\"sasl.jaas.config\":\"secret\"}");
        p.setConnectionStatus("SUCCESS");
        p.setLastTestedAt(new Timestamp(0L));
        p.setLastTestMessage("Connected successfully -- cluster \"pf-cluster\" with 3 broker(s).");
        p.setIsDefault(true);
        p.setStatus(Status.Active);
        return p;
    }

    private static KafkaConnectionProfile ownedBy(long tenantId, long id, Status status) {
        KafkaConnectionProfile p = new KafkaConnectionProfile();
        p.setKafkaConnectionProfileId(id);
        p.setTenantId(tenantId);
        p.setProfileName("Workspace broker " + id);
        p.setBootstrapServers("tenant-kafka:9092");
        p.setSecurityProtocol("PLAINTEXT");
        p.setIsDefault(false);
        p.setStatus(status);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static List<KafkaConnectionProfileDto> rows(ResponseDto response) {
        return (List<KafkaConnectionProfileDto>) response.getData();
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 4537L, "demo-admin@example.com");
    }

    @Test
    void aWorkspaceWithNoKafkaIsShownThePlatformDefaultReadOnly() throws Exception {
        this.actAsTenant(NO_KAFKA);
        when(this.profileRepository.findVisibleToTenant(NO_KAFKA, Status.Delete)).thenReturn(Collections.emptyList());
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(platformDefault()));

        List<KafkaConnectionProfileDto> rows = rows(this.service.fetchAllProfiles());

        assertThat(rows).hasSize(1);
        KafkaConnectionProfileDto dto = rows.get(0);
        assertThat(dto.getKafkaConnectionProfileId()).isEqualTo(PLATFORM_DEFAULT);
        assertThat(dto.getProfileName()).isEqualTo("Platform Local Broker [PF]");
        assertThat(dto.getTenantId()).isNull();
        assertThat(dto.getIsDefault()).isTrue();
        assertThat(dto.getPlatform()).isTrue();
        assertThat(dto.getReadOnly()).isTrue();
        // Enough to recognise it, nothing to reach it with.
        assertThat(dto.getEnvironmentLabel()).isEqualTo("local");
        assertThat(dto.getSecurityProtocol()).isEqualTo("SASL_SSL");
        assertThat(dto.getStatus()).isEqualTo(Status.Active);
        assertThat(dto.getBootstrapServers()).isNull();
        assertThat(dto.getSaslUsername()).isNull();
        assertThat(dto.getAdditionalProperties()).isNull();
        assertThat(dto.getLastTestMessage()).isNull();
        assertThat(dto.getSslKeystoreBucket()).isNull();
        assertThat(dto.getSslKeystoreLocation()).isNull();
        assertThat(dto.getSslTruststoreBucket()).isNull();
        assertThat(dto.getSslTruststoreLocation()).isNull();
        assertThat(dto.getSaslPassword()).isNull();
        assertThat(dto.getSslKeystorePassword()).isNull();
        assertThat(dto.getSslKeystorePasswordEnc()).isNull();
        assertThat(dto.getSslKeyPassword()).isNull();
        assertThat(dto.getSslKeyPasswordEnc()).isNull();
        assertThat(dto.getSslTruststorePassword()).isNull();
        assertThat(dto.getSslTruststorePasswordEnc()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePlatformRowCarriesNoPlatformAdminsName() throws Exception {
        this.actAsTenant(NO_KAFKA);
        when(this.profileRepository.findVisibleToTenant(NO_KAFKA, Status.Delete)).thenReturn(Collections.emptyList());
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(platformDefault()));

        // The list is handed over and then grown, so what it held has to be read at the call.
        List<List<AuditNamed>> named = new ArrayList<>();
        doAnswer(call -> { named.add(new ArrayList<>((List<AuditNamed>) call.getArgument(0))); return null; })
            .when(this.userNameResolver).attachToDtos(any(), any(), any());

        List<KafkaConnectionProfileDto> rows = rows(this.service.fetchAllProfiles());

        assertThat(rows).hasSize(1);
        assertThat(named).hasSize(1);
        assertThat(named.get(0)).isEmpty();
    }

    @Test
    void aWorkspaceWithKafkaOfItsOwnIsNotShownThePlatformDefault() throws Exception {
        this.actAsTenant(OWN_KAFKA);
        // Inactive, and still its own: the resolver refuses this workspace rather than borrow the platform's.
        when(this.profileRepository.findVisibleToTenant(OWN_KAFKA, Status.Delete))
            .thenReturn(Collections.singletonList(ownedBy(OWN_KAFKA, 77L, Status.Inactive)));

        List<KafkaConnectionProfileDto> rows = rows(this.service.fetchAllProfiles());

        assertThat(rows).extracting(KafkaConnectionProfileDto::getKafkaConnectionProfileId).containsExactly(77L);
        assertThat(rows.get(0).getReadOnly()).isFalse();
        assertThat(rows.get(0).getPlatform()).isFalse();
        assertThat(rows.get(0).getBootstrapServers()).isEqualTo("tenant-kafka:9092");
        verify(this.profileRepository, never()).findByTenantIdIsNullAndIsDefaultTrueAndStatus(any());
    }

    @Test
    void aWorkspaceWithNoKafkaAndNoPlatformDefaultIsShownNothing() throws Exception {
        this.actAsTenant(NO_KAFKA);
        when(this.profileRepository.findVisibleToTenant(NO_KAFKA, Status.Delete)).thenReturn(Collections.emptyList());
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active)).thenReturn(Optional.empty());

        assertThat(rows(this.service.fetchAllProfiles())).isEmpty();
    }

    @Test
    void aPlatformAdminSeesWhatItSawBefore() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "owner@example.com");
        when(this.profileRepository.findVisibleToPlatformAdmin(Status.Delete))
            .thenReturn(Arrays.asList(platformDefault(), ownedBy(OWN_KAFKA, 77L, Status.Active)));

        List<KafkaConnectionProfileDto> rows = rows(this.service.fetchAllProfiles());

        assertThat(rows).extracting(KafkaConnectionProfileDto::getKafkaConnectionProfileId).containsExactly(PLATFORM_DEFAULT, 77L);
        KafkaConnectionProfileDto platform = rows.get(0);
        assertThat(platform.getPlatform()).isTrue();
        assertThat(platform.getReadOnly()).isFalse();
        assertThat(platform.getBootstrapServers()).isEqualTo("platform-kafka.internal:9092");
        assertThat(platform.getSaslUsername()).isEqualTo("platform-etl");
        assertThat(platform.getSslKeystoreLocation()).isEqualTo("kafka-secrets/1/keystore.p12");
        assertThat(platform.getAdditionalProperties()).isEqualTo("{\"sasl.jaas.config\":\"secret\"}");
        assertThat(platform.getLastTestMessage()).startsWith("Connected successfully");
        assertThat(platform.getSaslPassword()).isNull();
        assertThat(rows.get(1).getPlatform()).isFalse();
        assertThat(rows.get(1).getReadOnly()).isFalse();
        verify(this.profileRepository, never()).findByTenantIdIsNullAndIsDefaultTrueAndStatus(any());
    }

    // ---- shown is not reachable: every write and every probe still refuses it ----------------

    private void platformDefaultIsFindable() {
        lenient().when(this.profileRepository.findById(PLATFORM_DEFAULT)).thenReturn(Optional.of(platformDefault()));
    }

    private KafkaConnectionProfileDto editOfPlatformDefault() {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setKafkaConnectionProfileId(PLATFORM_DEFAULT);
        dto.setProfileName("renamed");
        dto.setBootstrapServers("platform-kafka.internal:9092");
        dto.setSecurityProtocol("SASL_SSL");
        dto.setSaslMechanism("SCRAM-SHA-512");
        dto.setSaslUsername("platform-etl");
        return dto;
    }

    @Test
    void aTenantCannotEditThePlatformDefault() throws Exception {
        this.actAsTenant(NO_KAFKA);
        this.platformDefaultIsFindable();

        ResponseDto response = this.service.updateProfile(this.editOfPlatformDefault());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aTenantCannotDeleteThePlatformDefault() throws Exception {
        this.actAsTenant(NO_KAFKA);
        this.platformDefaultIsFindable();

        ResponseDto response = this.service.deleteProfile(PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aTenantCannotMoveThePlatformDefault() throws Exception {
        this.actAsTenant(NO_KAFKA);
        this.platformDefaultIsFindable();

        ResponseDto response = this.service.setAsDefault(PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.profileRepository, never()).clearPlatformDefaultExcept(anyLong());
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aTenantCannotTestThePlatformDefaultWithItsStoredSecrets() throws Exception {
        this.actAsTenant(NO_KAFKA);
        this.platformDefaultIsFindable();

        ResponseDto response = this.service.testConnection(this.editOfPlatformDefault());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.kafkaTemplateProvider, never()).commonClientProps(any());
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aTenantCannotNameThePlatformDefaultForATopicTest() throws Exception {
        this.actAsTenant(NO_KAFKA);
        this.platformDefaultIsFindable();

        ResponseDto response = this.service.testTopicConnection("orders", PLATFORM_DEFAULT);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.kafkaTemplateProvider, never()).commonClientProps(any());
    }
}
