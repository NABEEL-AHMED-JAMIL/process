package process.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class KafkaSecretPathTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 31);

    @Test
    void buildsTheAgreedLayout() {
        KafkaSecretPath path = KafkaSecretPath.newUpload(1248L, "truststore.p12", DAY);
        assertThat(path.key())
            .startsWith("kafka-secrets/1248/")
            .endsWith("/2026-08-31/truststore.p12");
        assertThat(path.prefix()).endsWith("/");
        assertThat(path.key()).isEqualTo(path.prefix() + "truststore.p12");
    }

    @Test
    void everyUploadGetsItsOwnFolderSoARotatedCertificateCannotOverwriteALiveOne() {
        String first = KafkaSecretPath.newUpload(1248L, "ca.pem", DAY).prefix();
        String second = KafkaSecretPath.newUpload(1248L, "ca.pem", DAY).prefix();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aSiblingStaysInTheSameUploadFolder() {
        KafkaSecretPath certificate = KafkaSecretPath.newUpload(7L, "ca.pem", DAY);
        KafkaSecretPath generated = certificate.sibling("truststore.p12");
        assertThat(generated.prefix()).isEqualTo(certificate.prefix());
        assertThat(generated.getUploadId()).isEqualTo(certificate.getUploadId());
    }

    @Test
    void roundTripsThroughParse() {
        KafkaSecretPath path = KafkaSecretPath.newUpload(1248L, "keystore.p12", DAY);
        KafkaSecretPath parsed = KafkaSecretPath.parse(path.key());
        assertThat(parsed).isNotNull();
        assertThat(parsed.getAppUserId()).isEqualTo(1248L);
        assertThat(parsed.getUploadedOn()).isEqualTo(DAY);
        assertThat(parsed.getFileName()).isEqualTo("keystore.p12");
        assertThat(parsed.getUploadId()).isEqualTo(path.getUploadId());
    }

    /**
     * Ownership is not decided here, and these tests no longer pretend it is.
     *
     * Two tests used to sit at this point, one of them labelled "the bug this whole class exists
     * to make impossible", asserting that user 1248 does not own "kafka-secrets/12480/...". They
     * exercised KafkaSecretPath.isOwnedBy -- a helper nothing in src/main ever called. The rule
     * that actually decides is KafkaSecretServiceImpl.canUseObject, which compares the parsed id
     * itself, so both tests would have kept passing if canUseObject were changed to a string
     * prefix tomorrow: they guarded a method, not the behaviour they were named for.
     *
     * The helper has been deleted and the cases live where the decision does --
     * KafkaSecretAccessTest.aLongerUserIdDoesNotSatisfyAShorterOne for the id, and
     * aKeyThatIsNotInTheAgreedLayoutIsRefused and anEmptyContextIsRefused for the rest. What is
     * left in this file is what this class is genuinely responsible for: building a key and
     * parsing one back, in agreement with each other.
     */

    @ParameterizedTest
    @ValueSource(strings = {
        "kafka-secrets/1248/../9999/2026-08-31/ca.pem",
        "kafka-secrets/1248/uuid/2026-08-31/..\\ca.pem",
        "kafka-secrets//1248/uuid/2026-08-31/ca.pem",
        "kafka-secrets/notanumber/uuid/2026-08-31/ca.pem",
        "kafka-secrets/1248/uuid/not-a-date/ca.pem",
        "kafka-secrets/1248/uuid/2026-08-31",
        "other-root/1248/uuid/2026-08-31/ca.pem",
    })
    void refusesAnythingThatIsNotExactlyTheLayout(String key) {
        // Unparseable is the whole assertion: canUseObject reads the owner out of the parsed
        // path, so a key that does not parse belongs to nobody and is refused to everybody.
        assertThat(KafkaSecretPath.parse(key)).isNull();
    }

    /**
     * Only the spelling this class writes counts as one of ours.
     *
     * Long.parseLong reads "01248" and "+1248" as 1248, so either spelling parsed as user 1248's
     * key while the storage backend would go to a folder of that literal name -- the owner the
     * request is authorised against and the object it then reads are not the same place.
     *
     * None of the cases above reaches that rule: every one of them is turned away by the
     * traversal, length or root test first, so deleting the round trip from parse() left all
     * seven of them green.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "kafka-secrets/01248/uuid/2026-08-31/ca.pem",
        "kafka-secrets/+1248/uuid/2026-08-31/ca.pem",
    })
    void anIdSpeltDifferentlyFromTheFolderItNamesIsRefused(String key) {
        assertThat(KafkaSecretPath.parse(key)).isNull();
    }

    /** The control for the two above: the same key, the id spelt as this class writes it. */
    @Test
    void theSameKeyWithTheIdSpeltAsWrittenIsAccepted() {
        KafkaSecretPath parsed = KafkaSecretPath.parse("kafka-secrets/1248/uuid/2026-08-31/ca.pem");
        assertThat(parsed).isNotNull();
        assertThat(parsed.getAppUserId()).isEqualTo(1248L);
    }

    @Test
    void aTraversingFilenameCollapsesToItsLastSegment() {
        KafkaSecretPath path = KafkaSecretPath.newUpload(5L, "../../../etc/passwd", DAY);
        assertThat(path.getFileName()).isEqualTo("passwd");
        assertThat(path.key()).doesNotContain("..");
    }

    @ParameterizedTest
    @ValueSource(strings = { "a b.pem", "ca;rm -rf.pem", "ca\0.pem", "café.pem" })
    void keepsOnlyHarmlessCharactersInAFilename(String requested) {
        String cleaned = KafkaSecretPath.safeFileName(requested);
        assertThat(cleaned).matches("[A-Za-z0-9._-]+");
    }

    /**
     * A dot is a legal filename character, so a run of them survived cleaning -- and parse()
     * refuses any key containing "..", which left the uploader locked out of their own file.
     */
    @Test
    void aFilenameKeepingARunOfDotsStaysReadableAsItsOwners() {
        KafkaSecretPath path = KafkaSecretPath.newUpload(1248L, "truststore..p12", DAY);
        assertThat(path.getFileName()).isEqualTo("truststore.p12");
        assertThat(KafkaSecretPath.parse(path.key())).isNotNull();
        assertThat(KafkaSecretPath.parse(path.key()).getAppUserId())
            .as("the uploader has to be readable back out of the key, or canUseObject refuses"
                + " them their own file")
            .isEqualTo(1248L);
    }

    @Test
    void fallsBackToANameWhenNothingUsableSurvives() {
        assertThat(KafkaSecretPath.safeFileName("...")).isEqualTo("upload");
        assertThat(KafkaSecretPath.safeFileName("   ")).isEqualTo("upload");
        assertThat(KafkaSecretPath.safeFileName(null)).isEqualTo("upload");
    }

    @Test
    void refusesToBuildAPathWithNoUser() {
        assertThatThrownBy(() -> KafkaSecretPath.newUpload(null, "ca.pem", DAY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signed-in user");
    }

}
