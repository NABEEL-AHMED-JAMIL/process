package process.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.directory.IdentityTopics;
import process.outbox.OutboxWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /internal/identity/events (MIG-166): identity-service's relay hands over its lifecycle events, and they go
 * into platform_outbox for the outbox relay to publish on the two identity topics -- the way Identity's
 * notices already reach the bus, since Identity keeps no Kafka client of its own.
 *
 * All or nothing, in the order given, and idempotent: an event whose event_id the outbox already holds is not
 * written again, because the relay re-sends a batch whose acknowledgement it never recorded. Only the two
 * identity topics, and only an event whose type belongs to its topic: this is not a way to publish anything
 * else. POST, service token only; the gateway keeps /internal inside.
 */
@RestController
@RequestMapping("/internal/identity")
public class InternalIdentityEventsRestApi {

    static final int MAX_EVENTS = 500;

    private final Logger logger = LoggerFactory.getLogger(InternalIdentityEventsRestApi.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final byte[] token;

    @Autowired
    public InternalIdentityEventsRestApi(OutboxWriter outbox, PlatformTransactionManager transactions,
        @Value("${internal.service-token:}") String token) {
        this(outbox, new TransactionTemplate(transactions), token);
    }

    InternalIdentityEventsRestApi(OutboxWriter outbox, TransactionTemplate transaction, String token) {
        this.outbox = outbox;
        this.transaction = transaction;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {events: [{eventId, topic, key, event}]}. Answers {accepted, duplicates}. */
    @PostMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> events(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Object given = body == null ? null : body.get("events");
        if (!(given instanceof Collection) || ((Collection<?>) given).size() > MAX_EVENTS) {
            return refuse("No readable events.");
        }
        List<String[]> events = new ArrayList<>();
        for (Object item : (Collection<?>) given) {
            String[] event = this.read(item);
            if (event == null) {
                return refuse("An event is not an identity lifecycle event on its own topic; nothing was taken.");
            }
            events.add(event);
        }
        int[] counts = this.transaction.execute(status -> {
            int accepted = 0;
            for (String[] event : events) {
                if (this.outbox.writeOnce(event[1], event[2], event[0], event[3])) {
                    accepted++;
                }
            }
            return new int[] {accepted, events.size() - accepted};
        });
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("accepted", counts[0]);
        answer.put("duplicates", counts[1]);
        return ResponseEntity.ok(answer);
    }

    /** {eventId, topic, key, event}, or null when it is not one of Identity's events on its own topic. */
    private String[] read(Object item) {
        if (!(item instanceof Map)) {
            return null;
        }
        Map<?, ?> row = (Map<?, ?>) item;
        String eventId = text(row.get("eventId"));
        String topic = text(row.get("topic"));
        String key = text(row.get("key"));
        String event = text(row.get("event"));
        if (eventId == null || eventId.length() > 36 || key == null || event == null) {
            return null;
        }
        String prefix = IdentityTopics.USER.equals(topic) ? "user." : IdentityTopics.TENANT.equals(topic) ? "tenant." : null;
        if (prefix == null) {
            return null;
        }
        try {
            JsonNode type = this.json.readTree(event).get("eventType");
            if (type == null || !type.asText().startsWith(prefix)) {
                return null;
            }
        } catch (Exception unreadable) {
            return null;
        }
        return new String[] {eventId, topic, key, event};
    }

    private static String text(Object value) {
        return value == null || value.toString().trim().isEmpty() ? null : value.toString();
    }

    private ResponseEntity<?> refuse(String why) {
        this.logger.warn("Refused a batch of identity events: {}", why);
        return new ResponseEntity<>(Collections.singletonMap("message", why), HttpStatus.BAD_REQUEST);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused identity events without the internal token.");
        }
        return ok;
    }
}
