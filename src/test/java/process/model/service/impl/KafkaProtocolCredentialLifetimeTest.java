package process.model.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaSecretService;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a profile is allowed to keep once it is no longer speaking the protocol that used it.
 *
 * applyProfileDto has always cleared the SASL credentials when a profile moves off SASL -- the
 * mechanism, the username and the password together, so nothing is left half-configured. The TLS
 * half of that rule only cleared the three store passwords and wrote the bucket and location back
 * unconditionally, which left a row naming a PKCS12 whose password had been thrown away.
 *
 * That was not a quiet inconsistency. The console decides whether a truststore is still attached
 * by looking at the location, so on reopening it turned the private-CA switch back on and said
 * "keeping the truststore this profile was saved with"; the save succeeded, and the next dispatch
 * failed at the TLS handshake with no password for the store the row still pointed at. The screen
 * said twice that the store was intact.
 *
 * @author Nabeel Ahmed
 */
class KafkaProtocolCredentialLifetimeTest {

    /**
     * Stubbed to return something, not left bare. The SASL password is the one credential here
     * that applyProfileDto encrypts rather than taking pre-encrypted, so a mock answering null
     * would make "it was cleared" true before the clearing ran -- the assertion would pass with
     * the rule deleted. aProfileOnSaslKeepsItsPassword is the control that proves it did not.
     */
    private final EncryptionUtil encryptionUtil = mock(EncryptionUtil.class);

    private final KafkaConnectionProfileServiceImpl service = new KafkaConnectionProfileServiceImpl(
        mock(KafkaConnectionProfileRepository.class),
        mock(SourceTaskTypeRepository.class),
        mock(TenantTaskTypeKafkaRouteRepository.class),
        this.encryptionUtil,
        mock(KafkaTemplateProvider.class),
        mock(KafkaConnectionResolver.class),
        mock(UserNameResolver.class),
        mock(KafkaSecretService.class),
        mock(StorageConnectionRepository.class));

    KafkaProtocolCredentialLifetimeTest() {
        when(this.encryptionUtil.encrypt(anyString())).thenAnswer(call -> "enc-" + call.getArgument(0));
    }

    /** A profile carrying every TLS field a save can set, as one that had been running on SSL. */
    private KafkaConnectionProfileDto fullyConfigured(String securityProtocol) {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName("orders");
        dto.setBootstrapServers("broker:9093");
        dto.setSecurityProtocol(securityProtocol);
        dto.setSslTruststoreBucket("etl-bucket");
        dto.setSslTruststoreLocation("kafka-secrets/1248/abc/2026-09-01/truststore.p12");
        dto.setSslTruststorePasswordEnc("enc-truststore");
        dto.setSslKeystoreBucket("etl-bucket");
        dto.setSslKeystoreLocation("kafka-secrets/1248/abc/2026-09-01/keystore.p12");
        dto.setSslKeystorePasswordEnc("enc-keystore");
        dto.setSslKeyPasswordEnc("enc-key");
        return dto;
    }

    private KafkaConnectionProfile applied(KafkaConnectionProfileDto dto) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        ReflectionTestUtils.invokeMethod(this.service, "applyProfileDto", profile, dto);
        return profile;
    }

    @Test
    void aProfileMovedOffTlsKeepsNeitherItsStorePasswordsNorTheStoresTheyOpened() {
        KafkaConnectionProfile profile = this.applied(this.fullyConfigured("SASL_PLAINTEXT"));

        assertThat(profile.getSslTruststorePasswordEnc()).isNull();
        assertThat(profile.getSslKeystorePasswordEnc()).isNull();
        assertThat(profile.getSslKeyPasswordEnc()).isNull();
        assertThat(profile.getSslTruststoreLocation())
            .as("a location outliving its password is what made the console report an intact"
                + " truststore and the next handshake fail")
            .isNull();
        assertThat(profile.getSslTruststoreBucket()).isNull();
        assertThat(profile.getSslKeystoreLocation()).isNull();
        assertThat(profile.getSslKeystoreBucket()).isNull();
    }

    @Test
    void plaintextIsTreatedTheSameWay() {
        KafkaConnectionProfile profile = this.applied(this.fullyConfigured("PLAINTEXT"));

        assertThat(profile.getSslTruststoreLocation()).isNull();
        assertThat(profile.getSslKeystoreLocation()).isNull();
        assertThat(profile.getSslTruststorePasswordEnc()).isNull();
    }

    /** The other half of the rule: a profile still on TLS must keep everything it was given. */
    @Test
    void aProfileStillOnTlsKeepsItsStoresAndTheirPasswords() {
        for (String protocol : new String[] { "SSL", "SASL_SSL" }) {
            KafkaConnectionProfile profile = this.applied(this.fullyConfigured(protocol));

            assertThat(profile.getSslTruststoreLocation())
                .as("%s is a TLS protocol and must keep its stores", protocol)
                .isEqualTo("kafka-secrets/1248/abc/2026-09-01/truststore.p12");
            assertThat(profile.getSslTruststoreBucket()).isEqualTo("etl-bucket");
            assertThat(profile.getSslTruststorePasswordEnc()).isEqualTo("enc-truststore");
            assertThat(profile.getSslKeystoreLocation())
                .isEqualTo("kafka-secrets/1248/abc/2026-09-01/keystore.p12");
            assertThat(profile.getSslKeystorePasswordEnc()).isEqualTo("enc-keystore");
            assertThat(profile.getSslKeyPasswordEnc()).isEqualTo("enc-key");
        }
    }

    /** The rule this one was modelled on, asserted here so the two stay in step. */
    @Test
    void aProfileMovedOffSaslKeepsNoneOfItsSaslCredentials() {
        KafkaConnectionProfileDto dto = this.fullyConfigured("SSL");
        dto.setSaslMechanism("SCRAM-SHA-512");
        dto.setSaslUsername("orders-writer");
        dto.setSaslPassword("s3cret");

        KafkaConnectionProfile profile = this.applied(dto);

        assertThat(profile.getSaslMechanism()).isNull();
        assertThat(profile.getSaslUsername()).isNull();
        assertThat(profile.getSaslPassword()).isNull();
    }

    /**
     * The control for the test above. Without it, a mock encryptor answering null would satisfy
     * "the password was cleared" whether or not anything cleared it.
     */
    @Test
    void aProfileOnSaslKeepsItsPassword() {
        KafkaConnectionProfileDto dto = this.fullyConfigured("SASL_SSL");
        dto.setSaslMechanism("SCRAM-SHA-512");
        dto.setSaslUsername("orders-writer");
        dto.setSaslPassword("s3cret");

        KafkaConnectionProfile profile = this.applied(dto);

        assertThat(profile.getSaslPassword()).isEqualTo("enc-s3cret");
        assertThat(profile.getSaslUsername()).isEqualTo("orders-writer");
    }
}
