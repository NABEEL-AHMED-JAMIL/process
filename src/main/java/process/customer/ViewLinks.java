package process.customer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The embeddable run view's signed links (MIG-335, ADR-025 decision 10: "POST /v1/runs/{runId}/view-links answers a
 * signed, short-lived (15-minute) link to a read-only console page /embed/runs/{token}"). FileLinks' pattern for a run:
 * the token is the only permission, for one run of one workspace, made by one API client, until it expires.
 *
 * <b>The token</b> is {@code <payload>.<signature>}, both base64url: the payload "v1|tenant|runId|clientId|expires (epoch
 * seconds)", the signature its HMAC-SHA256 under a key derived from the internal service token for this use alone -- not
 * FileLinks' key, so a view token is never a file link and a file link never a view. It names its run, so it opens no
 * other; it carries nothing a person could not see on the page it opens.
 */
@Component
public class ViewLinks {

    /** How long a link opens its view (ADR-025: 15 minutes), and the shortest a portal may ask for. */
    public static final Duration VALID = Duration.ofMinutes(15);
    public static final Duration SHORTEST = Duration.ofMinutes(1);
    static final String VERSION = "v1";
    /** What a token looks like, before its signature is checked: two base64url parts. */
    public static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{8,512}\\.[A-Za-z0-9_-]{43}$");

    /** What a good link names. */
    public static final class Link {
        public final long tenantId;
        public final long runId;
        public final String clientId;
        public final Instant expiresAt;

        Link(long tenantId, long runId, String clientId, Instant expiresAt) {
            this.tenantId = tenantId;
            this.runId = runId;
            this.clientId = clientId;
            this.expiresAt = expiresAt;
        }
    }

    /** A link made now: its token and when it stops opening the view. */
    public static final class Issued {
        public final String token;
        public final Instant expiresAt;

        Issued(String token, Instant expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    /** How a presented token was judged. */
    public enum Verdict { GOOD, EXPIRED, NOT_OURS }

    /** A verdict, with the link when it is good or merely expired. */
    public static final class Checked {
        public final Verdict verdict;
        public final Link link;

        Checked(Verdict verdict, Link link) {
            this.verdict = verdict;
            this.link = link;
        }
    }

    private final byte[] key;
    private final Clock clock;

    @Autowired
    public ViewLinks(@Value("${internal.service-token:}") String serviceToken) {
        this(serviceToken, Clock.systemUTC());
    }

    ViewLinks(String serviceToken, Clock clock) {
        String secret = serviceToken == null ? "" : serviceToken.trim();
        this.key = secret.isEmpty() ? null : ("customer-api-view-link:" + secret).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** Whether links can be made and checked: not without the service token this service is given. */
    public boolean available() {
        return this.key != null;
    }

    /** A link to this run of this workspace for this client, valid {@code life} (between {@link #SHORTEST} and {@link #VALID}). */
    public Issued issue(long tenantId, long runId, String clientId, Duration life) {
        if (!this.available()) {
            throw new IllegalStateException("View links cannot be signed: internal.service-token is not set.");
        }
        if (clientId == null || clientId.isEmpty() || clientId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("A view link is made by an API client.");
        }
        Duration valid = life == null || life.compareTo(VALID) > 0 ? VALID : life.compareTo(SHORTEST) < 0 ? SHORTEST : life;
        Instant expires = this.clock.instant().plus(valid).truncatedTo(ChronoUnit.SECONDS);
        String payload = String.join("|", VERSION, String.valueOf(tenantId), String.valueOf(runId), clientId,
            String.valueOf(expires.getEpochSecond()));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Issued(encoded + "." + this.sign(encoded), expires);
    }

    /**
     * The link a token is: GOOD; EXPIRED when it is ours and was good once; NOT_OURS for anything else -- a token we did
     * not sign (a file link's included), a changed one, or none.
     */
    public Checked check(String token) {
        if (!this.available() || token == null || !SHAPE.matcher(token).matches()) {
            return new Checked(Verdict.NOT_OURS, null);
        }
        int dot = token.indexOf('.');
        String encoded = token.substring(0, dot);
        if (!MessageDigest.isEqual(this.sign(encoded).getBytes(StandardCharsets.UTF_8),
            token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return new Checked(Verdict.NOT_OURS, null);
        }
        Optional<Link> link = parse(encoded);
        if (!link.isPresent()) {
            return new Checked(Verdict.NOT_OURS, null);
        }
        if (!this.clock.instant().isBefore(link.get().expiresAt)) {
            return new Checked(Verdict.EXPIRED, link.get());
        }
        return new Checked(Verdict.GOOD, link.get());
    }

    private static Optional<Link> parse(String encoded) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 5 || !VERSION.equals(parts[0]) || parts[3].isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Link(Long.parseLong(parts[1]), Long.parseLong(parts[2]), parts[3],
                Instant.ofEpochSecond(Long.parseLong(parts[4]))));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("The view link could not be signed.", impossible);
        }
    }
}
