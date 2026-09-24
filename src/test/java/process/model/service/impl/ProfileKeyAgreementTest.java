package process.model.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.InputStream;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-175: process's own-picture guard agrees with Identity's and Storage's (C30; MIG-166/175 follow-up).
 *
 * Two guards in two services decide the same question from two sides. Identity's isOwnProfileKey says
 * which key a person may record as their picture; Storage's isOwnProfileObject says which avatar-bucket
 * object a non-admin may touch. If they disagree, a picture is uploaded that can never be shown, or a
 * row names an object its owner could never have written. So both run over one case table, committed
 * byte-identical in both repos -- storage-service has the same file and this same pinned hash in its
 * own ProfileKeyAgreementTest -- and an edit to the table on one side alone fails the pin.
 *
 * process still carries its pre-Identity copy of the rule (AppUserServiceImpl.updateOwnAvatar; the gateway sends
 * appUser.json to Identity since the cutover, but the endpoint answers anyone who reaches process directly), so it
 * runs over the same table, byte-identical in all three repos, with the same pin. It used to accept a backslash in
 * the file name and "null/profile/..." for a caller with no id.
 */
class ProfileKeyAgreementTest {

    static final String CASES = "/profile-key-agreement-cases.csv";
    /** The same constant is in storage-service's ProfileKeyAgreementTest. Change both, and the file in both. */
    static final String PINNED_SHA256 = "9594be36b425bb77dae012a26a3ebe851c34398b2433ea463714e056bc56379d";

    @ParameterizedTest(name = "[{index}] user {0}, key \"{1}\" -> {2}")
    @CsvFileSource(resources = CASES, numLinesToSkip = 1, nullValues = "<null>")
    void identitysGuardAnswersEveryCaseAsTheTableSays(Long appUserId, String key, boolean expected) {
        assertThat(AppUserServiceImpl.isOwnProfileKey(appUserId, key)).isEqualTo(expected);
    }

    @Test
    void theCaseTableIsTheOneStorageRunsToo() throws Exception {
        assertThat(sha256(CASES)).as("profile-key-agreement-cases.csv changed: change it in storage-service too, "
            + "byte for byte, and update PINNED_SHA256 in both ProfileKeyAgreementTests").isEqualTo(PINNED_SHA256);
    }

    static String sha256(String resource) throws Exception {
        try (InputStream in = ProfileKeyAgreementTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
    }
}
