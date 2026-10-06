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

/**
 * The customer API's signed file links (MIG-334, ADR-025 decision 3: "GET /v1/files/{fileId}: 302 to a signed link valid
 * 5 minutes"). GET /v1/files/{fileId} answers /v1/files/{fileId}/content?token=..., which needs no access token: the
 * token is the permission, for that one file of that one workspace, until it expires. A portal may hand it to a browser.
 *
 * <b>The token</b> is {@code <payload>.<signature>}, both base64url: the payload "v1|tenant|fileId|clientId|expires (epoch
 * seconds)", the signature its HMAC-SHA256 under a key derived from the internal service token for this use alone
 * (the way PublicForms signs its tickets), so it opens nothing else and nothing else opens it. A link names its file, so
 * it cannot be pointed at another; it never names a bucket, a storage key or a connection.
 */
@Component
public class FileLinks {

    /** How long a link opens its file (ADR-025: 5 minutes). */
    public static final Duration VALID = Duration.ofMinutes(5);
    static final String VERSION = "v1";

    /** What a good link names. */
    public static final class Link {
        public final long tenantId;
        public final String fileId;
        public final String clientId;
        public final Instant expiresAt;

        Link(long tenantId, String fileId, String clientId, Instant expiresAt) {
            this.tenantId = tenantId;
            this.fileId = fileId;
            this.clientId = clientId;
            this.expiresAt = expiresAt;
        }
    }

    /** A link made now: its token and when it stops opening the file. */
    public static final class Issued {
        public final String token;
        public final Instant expiresAt;

        Issued(String token, Instant expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    /** How a presented link was judged. */
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
    public FileLinks(@Value("${internal.service-token:}") String serviceToken) {
        this(serviceToken, Clock.systemUTC());
    }

    FileLinks(String serviceToken, Clock clock) {
        String secret = serviceToken == null ? "" : serviceToken.trim();
        this.key = secret.isEmpty() ? null : ("customer-api-file-link:" + secret).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** Whether links can be made and checked: not without the service token this service is given. */
    public boolean available() {
        return this.key != null;
    }

    /** A link to this file of this workspace for this client, valid {@link #VALID}. */
    public Issued issue(long tenantId, String fileId, String clientId) {
        if (!this.available()) {
            throw new IllegalStateException("File links cannot be signed: internal.service-token is not set.");
        }
        Instant expires = this.clock.instant().plus(VALID).truncatedTo(ChronoUnit.SECONDS);
        String payload = String.join("|", VERSION, String.valueOf(tenantId), fileId, clientId == null ? "" : clientId,
            String.valueOf(expires.getEpochSecond()));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Issued(encoded + "." + this.sign(encoded), expires);
    }

    /**
     * The link a token is, for this file: GOOD; EXPIRED when it is ours and was good once; NOT_OURS for anything else --
     * a token we did not sign, one for another file, or no token at all.
     */
    public Checked check(String fileId, String token) {
        if (!this.available() || token == null || fileId == null) {
            return new Checked(Verdict.NOT_OURS, null);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return new Checked(Verdict.NOT_OURS, null);
        }
        String encoded = token.substring(0, dot);
        if (!MessageDigest.isEqual(this.sign(encoded).getBytes(StandardCharsets.UTF_8),
            token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return new Checked(Verdict.NOT_OURS, null);
        }
        Optional<Link> link = parse(encoded);
        if (!link.isPresent() || !link.get().fileId.equals(fileId)) {
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
            if (parts.length != 5 || !VERSION.equals(parts[0])) {
                return Optional.empty();
            }
            return Optional.of(new Link(Long.parseLong(parts[1]), parts[2], parts[3].isEmpty() ? null : parts[3],
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
            throw new IllegalStateException("The file link could not be signed.", impossible);
        }
    }
}
