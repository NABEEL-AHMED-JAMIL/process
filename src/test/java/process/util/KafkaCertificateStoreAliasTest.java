package process.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a truststore built from two CAs that share a name.
 *
 * The alias of an entry is the certificate's common name, and a renewed root keeps the common name
 * of the one it replaces -- which is precisely the moment somebody uploads both, so that
 * connections keep working either side of the rotation. A keystore replaces an entry whose alias
 * is already taken rather than refusing it, so the two collapsed into one and the response still
 * said "built from 2 certificates". Whichever chain needed the dropped CA then failed at the
 * handshake, on another machine, with nothing pointing back here.
 *
 * @author Nabeel Ahmed
 */
class KafkaCertificateStoreAliasTest {

    private static final char[] PASSWORD = "unit-test-store".toCharArray();

    @Test
    void twoCasSharingACommonNameBothSurviveTheTruststore() throws Exception {
        X509Certificate retiring = SelfSignedCertificate.issue("Production Root CA").certificate();
        X509Certificate renewed = SelfSignedCertificate.issue("Production Root CA").certificate();
        // The premise: same subject, different certificates.
        assertThat(renewed.getSubjectX500Principal()).isEqualTo(retiring.getSubjectX500Principal());
        assertThat(renewed).isNotEqualTo(retiring);

        byte[] store = KafkaCertificateUtil.buildTruststore(Arrays.asList(retiring, renewed), PASSWORD);

        KeyStore opened = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        opened.load(new ByteArrayInputStream(store), PASSWORD);
        List<String> aliases = Collections.list(opened.aliases());
        assertThat(aliases).hasSize(2);

        List<X509Certificate> held = new ArrayList<>();
        for (String alias : aliases) {
            held.add((X509Certificate) opened.getCertificate(alias));
        }
        assertThat(held).containsExactlyInAnyOrder(retiring, renewed);
    }

}
