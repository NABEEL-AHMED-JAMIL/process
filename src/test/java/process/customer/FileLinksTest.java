package process.customer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-334: a file's signed link opens that one file of that one workspace for 5 minutes, and nothing else: another
 * file's id, a token we did not sign, a changed byte, an expired link or a service without its token open nothing.
 */
class FileLinksTest {

    static final String FILE = "01JC0FFEE0000000000000000A";
    static final Instant NOW = Instant.parse("2026-10-06T15:00:00Z");

    private static FileLinks at(Instant now) {
        return new FileLinks("the-service-token", Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aLinkOpensItsFileForFiveMinutes() {
        FileLinks.Issued issued = at(NOW).issue(2946L, FILE, "cl_portal");
        assertThat(issued.expiresAt).isEqualTo(NOW.plusSeconds(300));
        assertThat(issued.token).doesNotContain("/").doesNotContain("+").doesNotContain("=");

        FileLinks.Checked good = at(NOW.plusSeconds(299)).check(FILE, issued.token);
        assertThat(good.verdict).isEqualTo(FileLinks.Verdict.GOOD);
        assertThat(good.link.tenantId).isEqualTo(2946L);
        assertThat(good.link.clientId).isEqualTo("cl_portal");

        FileLinks.Checked late = at(NOW.plusSeconds(300)).check(FILE, issued.token);
        assertThat(late.verdict).isEqualTo(FileLinks.Verdict.EXPIRED);
        assertThat(late.link.tenantId).as("an expired link still says whose it was, for the access log").isEqualTo(2946L);
    }

    @Test
    void aLinkOpensNothingElse() {
        String token = at(NOW).issue(2946L, FILE, "cl_portal").token;
        FileLinks links = at(NOW);
        assertThat(links.check("01JC0FFEE0000000000000000B", token).verdict).as("another file").isEqualTo(FileLinks.Verdict.NOT_OURS);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(links.check(FILE, tampered).verdict).isEqualTo(FileLinks.Verdict.NOT_OURS);
        // The payload changed to name another workspace, the signature kept: refused.
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("v1|2947|" + FILE + "|cl_portal|" + NOW.plusSeconds(300).getEpochSecond()).getBytes()) + token.substring(token.indexOf('.'));
        assertThat(links.check(FILE, forged).verdict).isEqualTo(FileLinks.Verdict.NOT_OURS);
        assertThat(new FileLinks("another-token", Clock.fixed(NOW, ZoneOffset.UTC)).check(FILE, token).verdict)
            .as("signed under another key").isEqualTo(FileLinks.Verdict.NOT_OURS);
        for (String junk : new String[] {null, "", ".", "abc", "a.b.c", "!!!.???"}) {
            assertThat(links.check(FILE, junk).verdict).isEqualTo(FileLinks.Verdict.NOT_OURS);
        }
    }

    @Test
    void withoutTheServiceTokenNoLinkIsMadeOrOpened() {
        FileLinks none = new FileLinks("  ", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(none.available()).isFalse();
        assertThat(none.check(FILE, at(NOW).issue(2946L, FILE, "cl_portal").token).verdict).isEqualTo(FileLinks.Verdict.NOT_OURS);
    }
}
