package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.storage.remote.RemoteStorageDirectory;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Vulnerabilities review 2026-10-13, C5: an empty ssl.endpoint.identification.algorithm switches the broker's hostname
 * check off. A workspace administrator could set it, on a saved profile and on a Test connection, so any host holding
 * a certificate the truststore trusts would have been accepted for the workspace's broker. Now only a platform
 * administrator may switch it off; anyone may verify (https) or leave it to Kafka's default, which verifies.
 */
class KafkaHostnameVerificationTest {

    private final KafkaConnectionProfileServiceImpl service = new KafkaConnectionProfileServiceImpl(
        mock(KafkaConnectionProfileRepository.class), mock(SourceTaskTypeRepository.class),
        mock(TenantTaskTypeKafkaRouteRepository.class), mock(EncryptionUtil.class), mock(KafkaTemplateProvider.class),
        mock(KafkaConnectionResolver.class), mock(UserNameResolver.class), mock(KafkaSecretService.class),
        mock(RemoteStorageDirectory.class));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static KafkaConnectionProfileDto ssl(String algorithm) {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("orders");
        dto.setBootstrapServers("broker.example.com:9093");
        dto.setSecurityProtocol("SSL");
        dto.setSslEndpointIdentificationAlgorithm(algorithm);
        return dto;
    }

    private String refusal(String algorithm) {
        return this.service.endpointIdentificationRefusal(ssl(algorithm).getSslEndpointIdentificationAlgorithm());
    }

    @Test
    void aWorkspaceAdministratorCannotSwitchTheCheckOff() {
        TenantContext.set(1248L, "TENANT_ADMIN", 4537L, "admin@example.com");

        assertThat(refusal("")).isEqualTo("Only a platform administrator can switch off hostname verification for a Kafka connection.");
        assertThat(refusal("  ")).isNotNull();
        assertThat(refusal("https")).isNull();
        assertThat(refusal("HTTPS")).isNull();
        assertThat(refusal(null)).as("Kafka's default verifies the hostname").isNull();
    }

    @Test
    void theRefusalStopsASaveAndATestConnectionBeforeAnythingElse() {
        TenantContext.set(1248L, "TENANT_ADMIN", 4537L, "admin@example.com");

        Object answer = ReflectionTestUtils.invokeMethod(this.service, "validateProfile", ssl(""), (String) null);

        assertThat(answer).hasFieldOrPropertyWithValue("message",
            "Only a platform administrator can switch off hostname verification for a Kafka connection.");
    }

    @Test
    void aPlatformAdministratorMaySwitchItOffForABrokerWhoseNamesDoNotMatch() {
        TenantContext.set(null, "PLATFORM_ADMIN", 4639L, "qa-platform-admin@platform.local");

        assertThat(refusal("")).isNull();
    }

    @Test
    void anythingButHttpsOrEmptyIsRefusedForEveryone() {
        TenantContext.set(null, "PLATFORM_ADMIN", 4639L, "qa-platform-admin@platform.local");

        assertThat(refusal("none")).contains("must be https");
        assertThat(refusal("ldaps")).contains("must be https");
    }

    @Test
    void theStoredFormIsNullHttpsOrEmpty() {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        ReflectionTestUtils.invokeMethod(this.service, "applyProfileDto", profile, ssl(" HTTPS "));
        assertThat(profile.getSslEndpointIdentificationAlgorithm()).isEqualTo("https");

        ReflectionTestUtils.invokeMethod(this.service, "applyProfileDto", profile, ssl(null));
        assertThat(profile.getSslEndpointIdentificationAlgorithm()).isNull();

        ReflectionTestUtils.invokeMethod(this.service, "applyProfileDto", profile, ssl("  "));
        assertThat(profile.getSslEndpointIdentificationAlgorithm()).isEmpty();
    }
}
