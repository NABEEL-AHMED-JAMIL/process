package process.pipeline.items;

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

/**
 * MIG-420: the signed links an item's files are downloaded through as one zip ("Download all"). A link is made only after
 * the access check (RunItems), names one workspace, one person, one run, one item and one step (or all its steps), and
 * opens nothing else; it lasts {@link #VALID}. The token is {@code <payload>.<signature>}, both base64url: the payload
 * "items1|tenant|user|run|item|step|expires", the signature its HMAC-SHA256 under a key derived from the internal service
 * token for this use alone (as FileLinks signs the customer API's). It never names a bucket, a storage key or a file.
 */
@Component
public class ItemLinks {

    public static final Duration VALID = Duration.ofMinutes(2);
    static final String VERSION = "items1";

    /** What a good link names. */
    public static final class Link {
        public final long tenantId;
        public final long userId;
        public final long jobQueueId;
        public final String itemRef;
        public final String stepKey;
        public final Instant expiresAt;

        Link(long tenantId, long userId, long jobQueueId, String itemRef, String stepKey, Instant expiresAt) {
            this.tenantId = tenantId;
            this.userId = userId;
            this.jobQueueId = jobQueueId;
            this.itemRef = itemRef;
            this.stepKey = stepKey;
            this.expiresAt = expiresAt;
        }
    }

    private final byte[] key;
    private final Clock clock;

    @Autowired
    public ItemLinks(@Value("${internal.service-token:}") String serviceToken) {
        this(serviceToken, Clock.systemUTC());
    }

    ItemLinks(String serviceToken, Clock clock) {
        String secret = serviceToken == null ? "" : serviceToken.trim();
        this.key = secret.isEmpty() ? null : ("run-item-link:" + secret).getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public boolean available() {
        return this.key != null;
    }

    /** A link valid {@link #VALID}: its token and when it stops. */
    public String[] issue(long tenantId, long userId, long jobQueueId, String itemRef, String stepKey) {
        if (!this.available()) {
            throw new IllegalStateException("Item links cannot be signed: internal.service-token is not set.");
        }
        Instant expires = this.clock.instant().plus(VALID).truncatedTo(ChronoUnit.SECONDS);
        String payload = String.join("|", VERSION, String.valueOf(tenantId), String.valueOf(userId), String.valueOf(jobQueueId), itemRef,
            stepKey == null ? "" : stepKey, String.valueOf(expires.getEpochSecond()));
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new String[] {encoded + "." + this.sign(encoded), expires.toString()};
    }

    /** The link a token is, when it is ours, unexpired and whole; null otherwise (a refusal says only that). */
    public Link check(String token) {
        if (!this.available() || token == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return null;
        }
        String encoded = token.substring(0, dot);
        if (!MessageDigest.isEqual(this.sign(encoded).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
        if (parts.length != 7 || !VERSION.equals(parts[0])) {
            return null;
        }
        try {
            Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[6]));
            if (!this.clock.instant().isBefore(expires)) {
                return null;
            }
            return new Link(Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]), parts[4],
                parts[5].isEmpty() ? null : parts[5], expires);
        } catch (NumberFormatException notOurs) {
            return null;
        }
    }

    private String sign(String encoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
