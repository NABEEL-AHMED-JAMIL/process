package process.model.pojo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity's toString() (EntityStrings, MIG-261) is what a log line or an exception message shows. It wrote every
 * own column -- so a logged AppUser carried its password hash, a Kafka profile its SASL password and encrypted key
 * passwords, a run its callback-token hash. Secret-bearing text fields are left out; the harmless ones whose names
 * merely mention a password or token (a flag, a version, an expiry) stay, because they explain what happened.
 */
class EntityStringsSecretsTest {

    @Test
    void aUsersPasswordHashIsNotWritten() {
        AppUser user = new AppUser();
        user.setUsername("ops@example.com");
        user.setPassword("$2a$10$HASHHASHHASH");
        user.setMustChangePassword(true);

        String text = user.toString();

        assertThat(text).contains("ops@example.com").contains("mustChangePassword").doesNotContain("HASHHASHHASH");
    }

    @Test
    void aKafkaProfilesSecretsAreNotWritten() {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setProfileName("prod broker");
        profile.setSaslPassword("sasl-secret-value");
        profile.setSslKeystorePasswordEnc("enc-keystore");
        profile.setSslKeyPasswordEnc("enc-key");
        profile.setSslTruststorePasswordEnc("enc-truststore");

        String text = profile.toString();

        assertThat(text).contains("prod broker")
            .doesNotContain("sasl-secret-value").doesNotContain("enc-keystore").doesNotContain("enc-key")
            .doesNotContain("enc-truststore");
    }

    @Test
    void aRunsCallbackTokenHashIsNotWrittenButItsAttemptIs() {
        JobQueue run = new JobQueue();
        run.setCallbackTokenHash("tokenhashvalue");
        run.setCallbackTokenAttempt(2);

        String text = run.toString();

        assertThat(text).doesNotContain("tokenhashvalue").contains("callbackTokenAttempt");
    }
}
