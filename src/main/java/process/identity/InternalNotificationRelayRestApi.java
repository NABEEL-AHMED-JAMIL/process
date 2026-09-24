package process.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.notifications.MailExtras;
import process.notifications.NotificationPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/**
 * Identity's notices and mails, relayed into process's outbox (MIG-107): identity-service keeps no
 * outbox of its own, so what it raises -- a welcome with its temporary password, a reset, a person's
 * unread counter to forget -- goes out exactly as it did while Identity lived here, through the one
 * NotificationPort. POST, service token only; the gateway keeps /internal inside.
 */
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationRelayRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalNotificationRelayRestApi.class);
    private final NotificationPort notifications;
    private final byte[] token;
    private final ObjectMapper json = new ObjectMapper();

    public InternalNotificationRelayRestApi(NotificationPort notifications, @Value("${internal.service-token:}") String token) {
        this.notifications = notifications;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {tenantId, notice: NotificationCreated}. */
    @PostMapping(value = "/notice", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> notice(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        NotificationCreated notice;
        try {
            notice = this.json.convertValue(body == null ? null : body.get("notice"), NotificationCreated.class);
        } catch (IllegalArgumentException unreadable) {
            notice = null;
        }
        if (notice == null) {
            return new ResponseEntity<>(Collections.singletonMap("message", "No readable notice."), HttpStatus.BAD_REQUEST);
        }
        this.notifications.notificationCreated(tenantOf(body), notice);
        return ResponseEntity.ok(Collections.emptyMap());
    }

    /** Body {tenantId, mail: MailRequested, secret?}. Answers {result}: the outbox's own sentence. */
    @PostMapping(value = "/mail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> mail(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        MailRequested mail;
        try {
            mail = this.json.convertValue(body == null ? null : body.get("mail"), MailRequested.class);
        } catch (IllegalArgumentException unreadable) {
            mail = null;
        }
        if (mail == null) {
            return new ResponseEntity<>(Collections.singletonMap("result", "Error: no readable mail."), HttpStatus.BAD_REQUEST);
        }
        Object secret = body.get("secret");
        MailExtras extras = secret == null ? MailExtras.NONE : MailExtras.secret(secret.toString());
        return ResponseEntity.ok(Collections.singletonMap("result", this.notifications.mailRequested(tenantOf(body), mail, extras)));
    }

    /** Body {appUserId}. */
    @PostMapping(value = "/forgetRecipient", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> forgetRecipient(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        Object appUserId = body == null ? null : body.get("appUserId");
        if (!(appUserId instanceof Number)) {
            return new ResponseEntity<>(Collections.singletonMap("message", "No appUserId."), HttpStatus.BAD_REQUEST);
        }
        this.notifications.forgetRecipient(((Number) appUserId).longValue());
        return ResponseEntity.ok(Collections.emptyMap());
    }

    private static Long tenantOf(Map<String, Object> body) {
        Object tenantId = body == null ? null : body.get("tenantId");
        return tenantId instanceof Number ? ((Number) tenantId).longValue() : null;
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a notification relay without the internal token.");
        }
        return ok;
    }
}
