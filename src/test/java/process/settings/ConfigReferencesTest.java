package process.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-167: how a task payload names configuration -- ${config:KEY} and ${secret:KEY} -- and the save-time rule that
 * a tag whose name says it holds a credential may only hold a ${secret:KEY} reference, never the credential itself.
 */
class ConfigReferencesTest {

    @Test
    void referencesAreFoundWithTheirKind() {
        String payload = "<pipeline><bucket>${config:INPUT_BUCKET}</bucket><db_password>${secret:DB_PASSWORD}</db_password>"
            + "<note>copy ${config:INPUT_BUCKET} to ${config:OUTPUT_BUCKET}</note></pipeline>";

        assertThat(ConfigReferences.in(payload)).containsExactlyInAnyOrder(
            ConfigReferences.Reference.config("INPUT_BUCKET"), ConfigReferences.Reference.config("OUTPUT_BUCKET"),
            ConfigReferences.Reference.secret("DB_PASSWORD"));
        assertThat(ConfigReferences.in(null)).isEmpty();
        assertThat(ConfigReferences.in("<p><a>$</a><b>${other:X}</b></p>")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"${secret:db_password}", "${config:}", "${config:1ST}", "${secret:A-B}", "${config: KEY}"})
    void aReferenceThatNamesNoValidKeyIsRefused(String reference) {
        Optional<String> refusal = ConfigReferences.malformed("<pipeline><x>" + reference + "</x></pipeline>");

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains(reference).contains("UPPER_SNAKE");
    }

    @Test
    void wellFormedReferencesAreNotMalformed() {
        assertThat(ConfigReferences.malformed("<p><a>${config:A}</a><b>${secret:B_2}</b></p>")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "db_password", "DB_PASSWORD", "dbPassword", "passwd", "pwd", "user-password",
        "secret", "client_secret", "clientSecret", "token", "auth_token", "authToken", "api_key", "apiKey", "APIKey", "apikey",
        "access_key", "secret_access_key", "accessKey", "private_key", "privateKey", "credential", "credentials",
        "passphrase", "secret_key", "ssl.key.password"})
    void aTagNamedForACredentialIsOne(String name) {
        assertThat(ConfigReferences.looksLikeCredential(name)).as(name).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bucket", "max_tokens", "tokenizer", "access_key_id", "token_url", "password_file",
        "credential_path", "secret_name", "keys", "input_key", "key_columns", "pipeline"})
    void aTagNamedForSomethingAboutACredentialIsNot(String name) {
        assertThat(ConfigReferences.looksLikeCredential(name)).as(name).isFalse();
    }

    @Test
    void aLiteralCredentialIsRefusedAndTheRefusalNeverRepeatsIt() {
        Optional<String> refusal = ConfigReferences.credentialLiteral(
            "<pipeline><db_user>etl</db_user><db_password>hunter2-canary</db_password></pipeline>");

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("<db_password>").contains("${secret:").doesNotContain("hunter2-canary");
    }

    @Test
    void aSecretReferenceOrNothingAtAllIsAccepted() {
        assertThat(ConfigReferences.credentialLiteral("<p><db_password>${secret:DB_PASSWORD}</db_password></p>")).isEmpty();
        assertThat(ConfigReferences.credentialLiteral("<p><db_password>  ${secret:DB_PASSWORD}\n</db_password></p>")).isEmpty();
        assertThat(ConfigReferences.credentialLiteral("<p><db_password></db_password><api_key/></p>")).isEmpty();
        assertThat(ConfigReferences.credentialLiteral("<p><max_tokens>4000</max_tokens><access_key_id>AKIA1</access_key_id></p>")).isEmpty();
        assertThat(ConfigReferences.credentialLiteral(null)).isEmpty();
    }

    @Test
    void aConfigReferenceOrAMixIsNotASecret() {
        assertThat(ConfigReferences.credentialLiteral("<p><password>${config:DB_PASSWORD}</password></p>")).isPresent();
        assertThat(ConfigReferences.credentialLiteral("<p><password>x${secret:DB_PASSWORD}</password></p>")).isPresent();
        assertThat(ConfigReferences.credentialLiteral("<p><password>${secret:A}${secret:B}</password></p>")).isPresent();
    }

    @Test
    void theLiteralIsFoundWhereverItHides() {
        assertThat(ConfigReferences.credentialLiteral("<p><db><password><![CDATA[hunter2]]></password></db></p>")).as("CDATA").isPresent();
        assertThat(ConfigReferences.credentialLiteral("<p><connection password=\"hunter2\" user=\"etl\"/></p>")).as("attribute").isPresent();
        assertThat(ConfigReferences.credentialLiteral("<p><connection password=\"${secret:DB}\"/></p>")).as("attribute ref").isEmpty();
        assertThat(ConfigReferences.credentialLiteral("<p><DB_PASSWORD>hunter2</DB_PASSWORD></p>")).as("upper case").isPresent();
        assertThat(ConfigReferences.credentialLiteral("<p><credentials><user>etl</user><password>x</password></credentials></p>"))
            .as("nested").isPresent();
        // Not well-formed XML: still read, tag by tag.
        assertThat(ConfigReferences.credentialLiteral("<p><api_key>abc</api_key><unclosed></p>")).as("unparseable").isPresent();
    }

    /** A parent named for credentials holds other tags, not a value: only what it holds is judged. */
    @Test
    void aCredentialParentWithReferencedChildrenIsAccepted() {
        assertThat(ConfigReferences.credentialLiteral(
            "<p><credentials>\n  <user>etl</user>\n  <password>${secret:DB_PASSWORD}</password>\n</credentials></p>")).isEmpty();
    }

    @Test
    void anEntityOrDoctypeIsNeverExpanded() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><p><password>&x;</password></p>";

        assertThat(ConfigReferences.credentialLiteral(xxe)).isPresent();
    }
}
