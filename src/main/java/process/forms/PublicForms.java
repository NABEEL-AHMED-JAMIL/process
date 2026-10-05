package process.forms;

import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.security.TenantContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A form filled in through a share link by someone outside the workspace (MIG-278). The narrow public door: with a
 * valid link a visitor may read THAT form's fields and submit to it (with its file fields) -- nothing else, no list, no
 * submission, no other form, no lookup into another form's answers.
 *
 * <ul>
 *   <li>The link: unknown, tampered, revoked or the workspace's sharing turned off -> "not valid" (404, one sentence for
 *       all, so a guess learns nothing); expired or used up -> 410; a link that requires sign-in takes only a member of
 *       its own workspace.</li>
 *   <li>Bots: opening a link hands out a signed ticket (HMAC with the service token over the link, a nonce and the time).
 *       A submission needs a ticket of that link at least {@link #MIN_FILL} old and at most {@link #MAX_FILL}, used once,
 *       and an empty honeypot field. Uploads are the ticket's: no visitor can send another's file.</li>
 *   <li>Rate limits in Redis, shared by every replica, failing closed: per address, reads, uploads and submissions per
 *       minute; per link, submissions per hour.</li>
 * </ul>
 * The work runs as the link's workspace under its row security, with a role of its own ({@link FormShareLinks#LINK_ROLE})
 * that no other code grants anything to.
 */
@Service
public class PublicForms {

    static final String NOT_VALID = "This link is not valid. Ask whoever sent it for a new one.";
    static final String EXPIRED = "This link has expired. Ask whoever sent it for a new one.";
    static final String USED = "This link has already been used.";
    static final String CLOSED = "This form is closed.";
    static final String NO_LOOKUPS = "This form cannot be filled in through a link (it looks up answers of another form).";
    static final String SIGN_IN = "Sign in to the workspace that shared this form to fill it in.";
    static final String OTHER_WORKSPACE = "This form is for the people of the workspace that shared it. Sign in to that "
        + "workspace to fill it in.";
    static final String TOO_MANY = "Too many tries from here. Wait a minute and try again.";
    static final String TOO_FAST = "That was quicker than a person fills in a form. Wait a few seconds and send it again.";
    static final String STALE = "This page has been open too long. Reload it, then send the form again.";
    static final Duration MIN_FILL = Duration.ofSeconds(3);
    static final Duration MAX_FILL = Duration.ofHours(6);
    static final String HONEYPOT = "website";

    private static final Logger logger = LoggerFactory.getLogger(PublicForms.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A refusal with the HTTP status it answers with. */
    public static final class Refused extends RuntimeException {
        public final HttpStatus status;

        Refused(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** What may be done per minute (per address) and per hour (per link). */
    public static final class Limits {
        public int readsPerMinute = 60;
        public int uploadsPerMinute = 20;
        public int submitsPerMinute = 10;
        public int submitsPerLinkPerHour = 200;
    }

    private final FormShareLinks links;
    private final FormStore store;
    private final FormSubmissionService submissions;
    private final StringRedisTemplate redis;
    private final byte[] key;
    private final Limits limits;
    private final Clock clock;

    @Autowired
    public PublicForms(FormShareLinks links, FormStore store, FormSubmissionService submissions, StringRedisTemplate redis,
        @Value("${internal.service-token:}") String serviceToken) {
        this(links, store, submissions, redis, serviceToken, new Limits(), Clock.systemUTC());
    }

    PublicForms(FormShareLinks links, FormStore store, FormSubmissionService submissions, StringRedisTemplate redis, String serviceToken,
        Limits limits, Clock clock) {
        this.links = links;
        this.store = store;
        this.submissions = submissions;
        this.redis = redis;
        this.key = ("form-link-ticket:" + (serviceToken == null ? "" : serviceToken.trim())).getBytes(StandardCharsets.UTF_8);
        this.limits = limits;
        this.clock = clock;
    }

    // ---- the three calls -----------------------------------------------------------------------------------------

    /** The form behind a link: its name, description and fields, and the ticket a submission will need. */
    public Map<String, Object> open(String token, String address, Long signedInTenant) {
        this.limit("read:" + address, this.limits.readsPerMinute, 60);
        Opened o = this.opened(token, signedInTenant);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", o.form.name);
        view.put("description", o.form.description);
        view.put("version", o.form.version);
        view.put("fields", o.form.fields);
        view.put("requireSignIn", o.link.requireSignIn);
        view.put("expiresAt", o.link.expiresAt.toString());
        view.put("ticket", this.ticket(o.link.linkId));
        return view;
    }

    /** A file for one of the form's file or signature fields, held for this ticket's submission. */
    public ResponseDto upload(String token, String ticket, String fieldKey, String fileName, byte[] content, String address,
        Long signedInTenant, Long signedInUser) {
        this.limit("upload:" + address, this.limits.uploadsPerMinute, 60);
        Opened o = this.opened(token, signedInTenant);
        String nonce = this.checked(ticket, o.link.linkId, false);
        return this.asLink(o, signedInUser, () -> {
            ResponseDto stored = this.submissions.storeUpload(o.link.tenantId, o.form, fieldKey, fileName, content, signedInUser);
            if (SUCCESS.equals(stored.getStatus()) && stored.getData() instanceof Map) {
                Object id = ((Map<?, ?>) stored.getData()).get("uploadId");
                if (id instanceof Number) {
                    this.links.ticketUpload(o.link.tenantId, ((Number) id).longValue(), nonce);
                }
            }
            return stored;
        });
    }

    /** The submission: the ticket checked and spent, a use taken from the link, then received as any submission is. */
    public ResponseDto submit(String token, String ticket, Map<String, Object> answers, String honeypot, String address,
        Long signedInTenant, Long signedInUser, String signedInName) {
        this.limit("submit:" + address, this.limits.submitsPerMinute, 60);
        Opened o = this.opened(token, signedInTenant);
        this.limit("link:" + o.link.linkId, this.limits.submitsPerLinkPerHour, 3600);
        String nonce = this.checked(ticket, o.link.linkId, true);
        if (honeypot != null && !honeypot.trim().isEmpty()) {
            // A field no person sees was filled: answer as if it went through, keep nothing.
            logger.info("A share-link submission to form {} was dropped: its honeypot was filled.", o.form.formId);
            return new ResponseDto(SUCCESS, "Thank you: your submission was received.");
        }
        if (!this.links.take(o.link.tenantId, o.link.linkId)) {
            throw new Refused(HttpStatus.GONE, o.link.usedUp() ? USED : EXPIRED);
        }
        String who = signedInName != null ? signedInName : "Share link: " + (o.link.label == null ? "#" + o.link.linkId : o.link.label);
        return receipt(this.asLink(o, signedInUser, () -> this.submissions.receiveAs(o.link.tenantId, o.form, answers, signedInUser,
            who.length() > 255 ? who.substring(0, 255) : who, this.context(o, nonce),
            (tenant, submissionId) -> this.links.attach(tenant, submissionId, o.link.linkId))));
    }

    /**
     * What a visitor is told: received, or what to fix at each field. Never the submission's id, the job it started, its
     * run or why a run did not start -- those are the workspace's, read in its submissions page.
     */
    static ResponseDto receipt(ResponseDto answered) {
        if (SUCCESS.equals(answered.getStatus())) {
            return new ResponseDto(SUCCESS, "Thank you: your submission was received.");
        }
        Object data = answered.getData();
        if (data instanceof Map && ((Map<?, ?>) data).get("problems") instanceof Map) {
            return new ResponseDto(ERROR, answered.getMessage(), Collections.singletonMap("problems", ((Map<?, ?>) data).get("problems")));
        }
        return new ResponseDto(ERROR, answered.getMessage());
    }

    // ---- the link ------------------------------------------------------------------------------------------------

    private static final class Opened {
        final FormShareLinks.Link link;
        final FormStore.Form form;

        Opened(FormShareLinks.Link link, FormStore.Form form) {
            this.link = link;
            this.form = form;
        }
    }

    /** The link and its form, or the refusal a visitor is given. */
    private Opened opened(String token, Long signedInTenant) {
        Optional<FormShareLinks.Link> found = this.links.byToken(token);
        if (!found.isPresent() || "Revoked".equals(found.get().status)) {
            throw new Refused(HttpStatus.NOT_FOUND, NOT_VALID);
        }
        FormShareLinks.Link link = found.get();
        return RowSecurity.forTenant(link.tenantId, () -> {
            if (!this.links.enabled(link.tenantId)) {
                throw new Refused(HttpStatus.NOT_FOUND, NOT_VALID);
            }
            if (link.expired(this.clock.instant())) {
                throw new Refused(HttpStatus.GONE, EXPIRED);
            }
            if (link.usedUp()) {
                throw new Refused(HttpStatus.GONE, USED);
            }
            // 401 only when nobody is signed in: the console answers a 401 on a live session by refreshing it and,
            // when that does not help, signing the person out -- someone of another workspace is told, not logged out.
            if (link.requireSignIn && signedInTenant == null) {
                throw new Refused(HttpStatus.UNAUTHORIZED, SIGN_IN);
            }
            if (link.requireSignIn && signedInTenant != link.tenantId) {
                throw new Refused(HttpStatus.FORBIDDEN, OTHER_WORKSPACE);
            }
            FormStore.Form form = this.store.find(link.tenantId, link.formId).orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, NOT_VALID));
            if (!FormStore.ACTIVE.equals(form.status)) {
                throw new Refused(HttpStatus.GONE, CLOSED);
            }
            if (form.fields.stream().anyMatch(f -> FormFields.LOOKUP.equals(f.getType()))) {
                throw new Refused(HttpStatus.GONE, NO_LOOKUPS);
            }
            return new Opened(link, form);
        });
    }

    /** Runs as the link's workspace -- its row security and the link's own role -- and puts the caller back after. */
    private <T> T asLink(Opened o, Long person, Supplier<T> work) {
        Long tenant = TenantContext.getTenantId();
        String role = TenantContext.getUserRole();
        Long user = TenantContext.getAppUserId();
        String name = TenantContext.getUsername();
        TenantContext.set(o.link.tenantId, FormShareLinks.LINK_ROLE, person, "share-link-" + o.link.linkId);
        try {
            return RowSecurity.forTenant(o.link.tenantId, work::get);
        } finally {
            if (tenant == null && role == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(tenant, role, user, name);
            }
        }
    }

    /** Uploads are the ticket's; a link's form has no lookups to answer. */
    private FormFields.Context context(Opened o, String nonce) {
        return new FormFields.Context() {
            @Override
            public List<String> lookupValues(FormField field) {
                return List.of();
            }

            @Override
            public Optional<FormFields.Upload> upload(String fieldKey, long uploadId) {
                return PublicForms.this.links.openUpload(o.link.tenantId, o.form.formId, fieldKey, nonce, uploadId);
            }
        };
    }

    // ---- tickets -------------------------------------------------------------------------------------------------

    /** "<linkId>.<issued epoch ms>.<nonce>.<hmac>", every part checked on the way back. */
    String ticket(long linkId) {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String body = linkId + "." + this.clock.millis() + "." + nonce;
        return body + "." + this.sign(body);
    }

    /** The ticket's nonce when it is this link's, signed by us, and (for a submission) old enough, fresh enough and unused. */
    String checked(String ticket, long linkId, boolean spend) {
        String[] parts = ticket == null ? new String[0] : ticket.split("\\.");
        if (parts.length != 4) {
            throw new Refused(HttpStatus.BAD_REQUEST, STALE);
        }
        String body = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(this.sign(body).getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))
            || !parts[0].equals(String.valueOf(linkId))) {
            throw new Refused(HttpStatus.BAD_REQUEST, STALE);
        }
        long issued;
        try {
            issued = Long.parseLong(parts[1]);
        } catch (NumberFormatException malformed) {
            throw new Refused(HttpStatus.BAD_REQUEST, STALE);
        }
        Duration age = Duration.ofMillis(this.clock.millis() - issued);
        if (age.compareTo(MAX_FILL) > 0 || age.isNegative()) {
            throw new Refused(HttpStatus.BAD_REQUEST, STALE);
        }
        if (spend) {
            if (age.compareTo(MIN_FILL) < 0) {
                throw new Refused(HttpStatus.TOO_MANY_REQUESTS, TOO_FAST);
            }
            Boolean first;
            try {
                first = this.redis.opsForValue().setIfAbsent("form-link:ticket:" + parts[2], "1", MAX_FILL.getSeconds(), TimeUnit.SECONDS);
            } catch (RuntimeException redisDown) {
                logger.warn("A share-link ticket could not be checked (Redis): {}", redisDown.getMessage());
                throw new Refused(HttpStatus.SERVICE_UNAVAILABLE, "Submissions cannot be checked right now. Try again in a minute.");
            }
            if (!Boolean.TRUE.equals(first)) {
                throw new Refused(HttpStatus.BAD_REQUEST, "This form was already sent from this page. Reload it to send another.");
            }
        }
        return parts[2];
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("The ticket could not be signed.", impossible);
        }
    }

    // ---- rate limits ---------------------------------------------------------------------------------------------

    /** One more of this kind in its window; refused past the limit, and refused when Redis cannot count (fail closed). */
    private void limit(String what, int most, int windowSeconds) {
        String key = "form-link:rate:" + what + ":" + (this.clock.millis() / 1000 / windowSeconds);
        Long count;
        try {
            count = this.redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                this.redis.expire(key, windowSeconds + 5L, TimeUnit.SECONDS);
            }
        } catch (RuntimeException redisDown) {
            logger.warn("A share-link rate limit could not be counted (Redis): {}", redisDown.getMessage());
            throw new Refused(HttpStatus.SERVICE_UNAVAILABLE, "This form cannot be opened right now. Try again in a minute.");
        }
        if (count != null && count > most) {
            throw new Refused(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
        }
    }
}
