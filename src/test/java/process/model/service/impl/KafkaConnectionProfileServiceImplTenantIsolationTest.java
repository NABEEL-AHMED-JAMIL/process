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
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a tenant may reach of a Kafka profile it does not own, which is now nothing at all.
 *
 * The platform's profiles were once listed to every tenant on purpose, and the rules here were
 * about limiting what came with them: not the bucket and key of the client keystore, because the
 * object browser hands that file to anyone who can name it, and not a writable test result, since
 * a tenant testing a shared profile would be recording an outcome the whole platform reads.
 *
 * findVisibleToTenant has since been narrowed to "tenantId = :tenantId", so a tenant no longer
 * sees or reaches those rows in the first place and both of those questions are moot for it. The
 * redaction and the write guard are kept as defence in depth and are still asserted below -- but
 * as the platform admin's own case, which is the one that can actually reach a row it does not
 * own. A test named for the old behaviour was removed rather than left to describe a rule the
 * code no longer has.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class KafkaConnectionProfileServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

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

        // An empty property map makes AdminClient.create fail on its own configuration, so a test that
        // reaches the probe stops there instead of opening a socket.
        lenient().when(this.kafkaTemplateProvider.commonClientProps(any()))
            .thenReturn(new HashMap<String, Object>());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private KafkaConnectionProfile profileOwnedBy(Long tenantId) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(700L);
        profile.setTenantId(tenantId);
        profile.setProfileName("prod-platform");
        profile.setBootstrapServers("stored-broker:9092");
        profile.setSecurityProtocol("SASL_SSL");
        profile.setSaslMechanism("SCRAM-SHA-512");
        profile.setSaslUsername("etl");
        profile.setSaslPassword("stored-cipher");
        profile.setSslKeystoreBucket("etl-bucket");
        profile.setSslKeystoreLocation("kafka-secrets/1724690000-a1b2c3/keystore.p12");
        profile.setSslKeystorePasswordEnc("stored-cipher");
        profile.setSslTruststoreBucket("etl-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/1724690000-a1b2c3/truststore.p12");
        profile.setSslTruststorePasswordEnc("stored-cipher");
        profile.setStatus(Status.Active);
        return profile;
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "tenant-user@example.com");
    }

    private void actAsPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@example.com");
    }

    @SuppressWarnings("unchecked")
    private List<KafkaConnectionProfileDto> fetched(ResponseDto response) {
        return (List<KafkaConnectionProfileDto>) response.getData();
    }

    private KafkaConnectionProfileDto editOf(KafkaConnectionProfile profile) {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setKafkaConnectionProfileId(profile.getKafkaConnectionProfileId());
        dto.setProfileName(profile.getProfileName());
        dto.setBootstrapServers(profile.getBootstrapServers());
        dto.setSecurityProtocol(profile.getSecurityProtocol());
        dto.setSaslMechanism(profile.getSaslMechanism());
        dto.setSaslUsername(profile.getSaslUsername());
        return dto;
    }

    @Test
    void aTenantAdminIsNotToldWhereThePlatformsKeystoreLives() throws Exception {
        when(this.profileRepository.findVisibleToTenant(TENANT_B, Status.Delete))
            .thenReturn(Collections.singletonList(this.profileOwnedBy(null)));

        this.actAsTenant(TENANT_B);
        KafkaConnectionProfileDto dto = this.fetched(this.service.fetchAllProfiles()).get(0);

        assertThat(dto.getSslKeystoreBucket()).isNull();
        assertThat(dto.getSslKeystoreLocation()).isNull();
        assertThat(dto.getSslTruststoreBucket()).isNull();
        assertThat(dto.getSslTruststoreLocation()).isNull();
        // The profile is still listed, and still says a secret is set -- only the path to it is gone.
        assertThat(dto.getProfileName()).isEqualTo("prod-platform");
        assertThat(dto.getSslKeystorePasswordConfigured()).isTrue();
    }

    @Test
    void aTenantAdminStillSeesTheLocationOnItsOwnProfile() throws Exception {
        when(this.profileRepository.findVisibleToTenant(TENANT_A, Status.Delete))
            .thenReturn(Collections.singletonList(this.profileOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_A);
        KafkaConnectionProfileDto dto = this.fetched(this.service.fetchAllProfiles()).get(0);

        assertThat(dto.getSslKeystoreBucket()).isEqualTo("etl-bucket");
        assertThat(dto.getSslKeystoreLocation()).isEqualTo("kafka-secrets/1724690000-a1b2c3/keystore.p12");
    }

    @Test
    void noProfileEverCarriesAPasswordBackToTheCaller() throws Exception {
        when(this.profileRepository.findVisibleToTenant(TENANT_A, Status.Delete))
            .thenReturn(Collections.singletonList(this.profileOwnedBy(TENANT_A)));

        this.actAsTenant(TENANT_A);
        KafkaConnectionProfileDto dto = this.fetched(this.service.fetchAllProfiles()).get(0);

        assertThat(dto.getSaslPassword()).isNull();
        assertThat(dto.getSslKeystorePassword()).isNull();
        assertThat(dto.getSslKeyPassword()).isNull();
        assertThat(dto.getSslTruststorePassword()).isNull();
    }

    /**
     * Stronger than it used to be, and the assertion changed with the rule rather than around it.
     *
     * This once proved a tenant admin could reach the platform's profile but not rewrite it. A
     * tenant now sees only its own profiles, so it cannot reach one at all -- which also closes
     * the probe that reaching it allowed: opening a connection to a broker of the caller's
     * choosing while the platform's own stored credentials came along in the copy.
     */
    @Test
    void aTenantAdminCannotReachThePlatformProfileAtAll() throws Exception {
        KafkaConnectionProfile platform = this.profileOwnedBy(null);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(platform));

        KafkaConnectionProfileDto dto = this.editOf(platform);
        dto.setBootstrapServers("attacker-broker:9092");
        dto.setSecurityProtocol("PLAINTEXT");

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        // Never probed at all, so no connection was opened on the platform's behalf.
        verify(this.kafkaTemplateProvider, never()).commonClientProps(any());
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void thePlatformAdminsOwnTestIsStillRecorded() throws Exception {
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(this.profileOwnedBy(null)));

        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setKafkaConnectionProfileId(700L);

        this.actAsPlatformAdmin();
        this.service.testConnection(dto);

        verify(this.profileRepository).save(any());
    }

    @Test
    void testingASavedProfileProbesTheUnsavedEditRatherThanTheStoredRow() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setBootstrapServers("edited-broker:9092");
        dto.setSecurityProtocol("PLAINTEXT");

        this.actAsTenant(TENANT_A);
        this.service.testConnection(dto);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.kafkaTemplateProvider).commonClientProps(captor.capture());
        assertThat(captor.getValue().getBootstrapServers()).isEqualTo("edited-broker:9092");
        // The probe runs on a copy, so the row itself is untouched apart from the recorded outcome.
        assertThat(stored.getBootstrapServers()).isEqualTo("stored-broker:9092");
        assertThat(stored.getSecurityProtocol()).isEqualTo("SASL_SSL");
    }

    @Test
    void aBlankPasswordOnATestStillMeansTheStoredOne() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        // An edit that leaves the credential exactly where it already went: same brokers, same
        // protocol, same mechanism, same account.
        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setEnvironmentLabel("staging");

        this.actAsTenant(TENANT_A);
        this.service.testConnection(dto);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.kafkaTemplateProvider).commonClientProps(captor.capture());
        assertThat(captor.getValue().getSaslPassword()).isEqualTo("stored-cipher");
        assertThat(captor.getValue().getEnvironmentLabel()).isEqualTo("staging");
    }

    /**
     * The password is never returned to anyone, so a caller who can spend it against a host of their
     * own choosing has read it: PLAIN puts it on the wire, SCRAM puts an offline-attackable proof of
     * it there. These four are the whole of the test: where it goes, over what, how, and as whom.
     */
    @Test
    void aTestCannotAimTheStoredPasswordAtABrokerTheCallerNamed() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setBootstrapServers("attacker.example.com:9092");
        dto.setSecurityProtocol("SASL_PLAINTEXT");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("Enter the SASL password again");
        // Nothing was built, so nothing was decrypted and nothing left the process.
        verify(this.kafkaTemplateProvider, never()).commonClientProps(any());
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aMechanismOrUsernameChangeCountsAsMovingTheCredentialToo() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));
        this.actAsTenant(TENANT_A);

        // PLAIN would hand the broker the secret itself where SCRAM hands it only a proof.
        KafkaConnectionProfileDto downgrade = this.editOf(stored);
        downgrade.setSaslMechanism("PLAIN");
        assertThat(this.service.testConnection(downgrade).getMessage()).contains("Enter the SASL password again");

        KafkaConnectionProfileDto otherAccount = this.editOf(stored);
        otherAccount.setSaslUsername("someone-else");
        assertThat(this.service.testConnection(otherAccount).getMessage()).contains("Enter the SASL password again");

        verify(this.kafkaTemplateProvider, never()).commonClientProps(any());
    }

    @Test
    void typingThePasswordAgainIsAllAnEditedBrokerNeeds() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));
        when(this.encryptionUtil.encrypt(any())).thenAnswer(call -> "cipher-of-" + call.getArgument(0));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setBootstrapServers("edited-broker:9092");
        dto.setSaslPassword("typed-by-the-operator");

        this.actAsTenant(TENANT_A);
        this.service.testConnection(dto);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.kafkaTemplateProvider).commonClientProps(captor.capture());
        assertThat(captor.getValue().getBootstrapServers()).isEqualTo("edited-broker:9092");
        assertThat(captor.getValue().getSaslPassword()).isEqualTo("cipher-of-typed-by-the-operator");
    }

    /**
     * Refusing this only on the test would be theatre: saving the new broker with the password field
     * blank and testing the row afterwards arrives at the same place by two requests instead of one.
     */
    @Test
    void theStoredPasswordCannotBeSavedOntoAnotherBrokerEither() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setBootstrapServers("attacker.example.com:9092");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.updateProfile(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("Enter the SASL password again");
        verify(this.profileRepository, never()).save(any());
        assertThat(stored.getBootstrapServers()).isEqualTo("stored-broker:9092");
    }

    /**
     * A profile with no password to borrow has nothing to protect, and a broker change on it is an
     * ordinary edit.
     */
    @Test
    void aProfileWithNoStoredPasswordStillMovesBrokersFreely() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        stored.setSecurityProtocol("PLAINTEXT");
        stored.setSaslMechanism(null);
        stored.setSaslUsername(null);
        stored.setSaslPassword(null);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setBootstrapServers("new-broker:9092");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.updateProfile(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
        verify(this.profileRepository).save(any());
    }

    @Test
    void aMechanismTheClientCannotBuildIsRejectedRatherThanStored() throws Exception {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("kerberos");
        dto.setBootstrapServers("broker:9092");
        dto.setSecurityProtocol("SASL_SSL");
        dto.setSaslMechanism("GSSAPI");
        dto.setSaslUsername("etl");
        dto.setSaslPassword("secret-from-the-operator");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.addProfile(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("saslMechanism");
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void anSslProfileNeedsNoTruststoreWhenTheBrokerIsSignedByAWellKnownCa() throws Exception {
        when(this.profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("public-cluster");
        dto.setBootstrapServers("broker.example.com:9093");
        dto.setSecurityProtocol("SSL");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.addProfile(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
    }

    @Test
    void switchingAProfileOffSaslTakesTheStoredPasswordWithIt() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setSecurityProtocol("PLAINTEXT");
        dto.setSaslMechanism(null);
        dto.setSaslUsername(null);

        this.actAsTenant(TENANT_A);
        this.service.updateProfile(dto);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.profileRepository).save(captor.capture());
        assertThat(captor.getValue().getSaslPassword()).isNull();
        assertThat(captor.getValue().getSslKeystorePasswordEnc()).isNull();
        assertThat(captor.getValue().getSslTruststorePasswordEnc()).isNull();
    }

    @Test
    void aClearFlagIsTheWayBackToNoSecretAtAll() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        stored.setSecurityProtocol("SSL");
        stored.setSaslMechanism(null);
        stored.setSaslUsername(null);
        stored.setSaslPassword(null);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setSslTruststoreBucket("etl-bucket");
        dto.setSslTruststoreLocation("kafka-secrets/1724690000-a1b2c3/truststore.p12");
        dto.setClearSslTruststorePassword(true);

        this.actAsTenant(TENANT_A);
        this.service.updateProfile(dto);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.profileRepository).save(captor.capture());
        assertThat(captor.getValue().getSslTruststorePasswordEnc()).isNull();
        assertThat(captor.getValue().getSslKeystorePasswordEnc()).isEqualTo("stored-cipher");
    }

    @Test
    void deletingAProfileTakesItsCredentialsWithIt() throws Exception {
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));
        when(this.sourceTaskTypeRepository.existsByKafkaConnectionProfileId(700L)).thenReturn(false);
        when(this.routeRepository.existsByKafkaConnectionProfileId(700L)).thenReturn(false);

        this.actAsTenant(TENANT_A);
        this.service.deleteProfile(700L);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.profileRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Status.Delete);
        assertThat(captor.getValue().getSaslPassword()).isNull();
        assertThat(captor.getValue().getSslKeystorePasswordEnc()).isNull();
        assertThat(captor.getValue().getSslKeyPasswordEnc()).isNull();
        assertThat(captor.getValue().getSslTruststorePasswordEnc()).isNull();
    }

    @Test
    void theStatusOnThePayloadIsAppliedOnUpdate() throws Exception {
        KafkaConnectionProfile stored = this.profileOwnedBy(TENANT_A);
        stored.setIsDefault(true);
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(stored));

        KafkaConnectionProfileDto dto = this.editOf(stored);
        dto.setStatus(Status.Inactive);

        this.actAsTenant(TENANT_A);
        this.service.updateProfile(dto);

        ArgumentCaptor<KafkaConnectionProfile> captor = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(this.profileRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Status.Inactive);
        // A profile nobody may select as the default must not stay the default either.
        assertThat(captor.getValue().getIsDefault()).isFalse();
    }

    @Test
    void deleteIsNotAStatusAnUpdateMaySet() throws Exception {
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        KafkaConnectionProfileDto dto = this.editOf(this.profileOwnedBy(TENANT_A));
        dto.setStatus(Status.Delete);

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.updateProfile(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void tenantBCannotTestTenantAsSavedProfile() throws Exception {
        when(this.profileRepository.findById(700L)).thenReturn(Optional.of(this.profileOwnedBy(TENANT_A)));

        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setKafkaConnectionProfileId(700L);

        this.actAsTenant(TENANT_B);
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getMessage()).contains("not found");
        verify(this.kafkaTemplateProvider, never()).commonClientProps(any());
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aFailedProbeSaysNothingAboutTheNetworkItRanIn() throws Exception {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("scan");
        dto.setBootstrapServers("10.0.4.17:9092");
        dto.setSecurityProtocol("PLAINTEXT");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.testConnection(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        assertThat(response.getMessage()).doesNotContain("10.0.4.17");
        assertThat(response.getMessage()).doesNotContain("bootstrap.servers");
    }

    /**
     * The topic test runs against whatever profile resolved for the tenant, which is usually the
     * platform's own row -- one the tenant asking is not shown the brokers or the stores of. Handing
     * back the client's own failure text told them anyway.
     */
    @Test
    void theTopicTestSaysNothingAboutTheClusterItAskedFor() throws Exception {
        KafkaConnectionProfile platform = this.profileOwnedBy(null);
        when(this.kafkaConnectionResolver.resolve(TENANT_A, null)).thenReturn(Optional.of(platform));

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.testTopicConnection("orders-in");

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        assertThat(response.getMessage()).doesNotContain("bootstrap.servers");
        assertThat(response.getMessage()).doesNotContain("stored-broker");
        assertThat(response.getMessage()).contains("the detail is in the server log");
    }

    @Test
    void aBootstrapListThatIsNotHostPortIsRefused() throws Exception {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("bad");
        dto.setBootstrapServers("http://broker.example.com/path");
        dto.setSecurityProtocol("PLAINTEXT");

        this.actAsTenant(TENANT_A);
        ResponseDto response = this.service.addProfile(dto);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("host:port");
        verify(this.profileRepository, never()).save(any());
    }
}
