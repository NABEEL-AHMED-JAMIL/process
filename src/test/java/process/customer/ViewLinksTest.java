package process.customer;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-335: a view link opens one run of one workspace, made by one client, for 15 minutes (or less, as asked), and nothing
 * else: a changed byte, a payload naming another run or workspace, a file link, a token under another key, or an expired
 * link open nothing.
 */
class ViewLinksTest {

    static final Instant NOW = Instant.parse("2026-10-06T15:00:00Z");

    private static ViewLinks at(Instant now) {
        return new ViewLinks("the-service-token", Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aLinkOpensItsRunForFifteenMinutesOrLessAsAsked() {
        ViewLinks.Issued issued = at(NOW).issue(2946L, 9100L, "cl_portal", null);
        assertThat(issued.expiresAt).isEqualTo(NOW.plusSeconds(900));
        assertThat(issued.token).matches(ViewLinks.SHAPE);

        ViewLinks.Checked good = at(NOW.plusSeconds(899)).check(issued.token);
        assertThat(good.verdict).isEqualTo(ViewLinks.Verdict.GOOD);
        assertThat(good.link.tenantId).isEqualTo(2946L);
        assertThat(good.link.runId).isEqualTo(9100L);
        assertThat(good.link.clientId).isEqualTo("cl_portal");
        assertThat(at(NOW.plusSeconds(900)).check(issued.token).verdict).isEqualTo(ViewLinks.Verdict.EXPIRED);

        assertThat(at(NOW).issue(2946L, 9100L, "cl_portal", Duration.ofSeconds(60)).expiresAt).isEqualTo(NOW.plusSeconds(60));
        assertThat(at(NOW).issue(2946L, 9100L, "cl_portal", Duration.ofHours(2)).expiresAt).as("never longer than 15 minutes")
            .isEqualTo(NOW.plusSeconds(900));
        assertThat(at(NOW).issue(2946L, 9100L, "cl_portal", Duration.ofSeconds(5)).expiresAt).as("never shorter than a minute")
            .isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void aLinkOpensNothingElse() {
        ViewLinks links = at(NOW);
        String token = links.issue(2946L, 9100L, "cl_portal", null).token;
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(links.check(tampered).verdict).isEqualTo(ViewLinks.Verdict.NOT_OURS);
        String expires = String.valueOf(NOW.plusSeconds(900).getEpochSecond());
        for (String payload : new String[] {"v1|2946|9101|cl_portal|" + expires, "v1|2947|9100|cl_portal|" + expires,
            "v1|2946|9100|cl_other|" + expires, "v1|2946|9100|cl_portal|" + (NOW.plusSeconds(86400).getEpochSecond())}) {
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + token.substring(token.indexOf('.'));
            assertThat(links.check(forged).verdict).as(payload).isEqualTo(ViewLinks.Verdict.NOT_OURS);
        }
        assertThat(new ViewLinks("another-token", Clock.fixed(NOW, ZoneOffset.UTC)).check(token).verdict)
            .as("signed under another key").isEqualTo(ViewLinks.Verdict.NOT_OURS);
        String fileLink = new FileLinks("the-service-token", Clock.fixed(NOW, ZoneOffset.UTC)).issue(2946L, "01JC0FFEE0000000000000000A",
            "cl_portal").token;
        assertThat(links.check(fileLink).verdict).as("a file link is not a view link").isEqualTo(ViewLinks.Verdict.NOT_OURS);
        assertThat(new FileLinks("the-service-token", Clock.fixed(NOW, ZoneOffset.UTC)).check("01JC0FFEE0000000000000000A", token).verdict)
            .as("nor a view link a file link").isEqualTo(FileLinks.Verdict.NOT_OURS);
        for (String junk : new String[] {null, "", ".", "abc", "a.b.c", "!!!.???", token + ".x", "../" + token}) {
            assertThat(links.check(junk).verdict).isEqualTo(ViewLinks.Verdict.NOT_OURS);
        }
    }

    @Test
    void withoutTheServiceTokenNoLinkIsMadeOrOpened() {
        ViewLinks none = new ViewLinks("  ", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(none.available()).isFalse();
        assertThat(none.check(at(NOW).issue(2946L, 9100L, "cl_portal", null).token).verdict).isEqualTo(ViewLinks.Verdict.NOT_OURS);
        assertThatThrownBy(() -> none.issue(2946L, 9100L, "cl_portal", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> at(NOW).issue(2946L, 9100L, null, null)).as("a link is an API client's").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileLinkOnAViewEndsWithTheView() {
        FileLinks files = new FileLinks("the-service-token", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(files.issue(2946L, "01JC0FFEE0000000000000000A", "cl_portal", NOW.plusSeconds(90)).expiresAt).isEqualTo(NOW.plusSeconds(90));
        assertThat(files.issue(2946L, "01JC0FFEE0000000000000000A", "cl_portal", NOW.plusSeconds(900)).expiresAt).isEqualTo(NOW.plusSeconds(300));
    }
}
