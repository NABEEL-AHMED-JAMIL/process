package process.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * /internal/kafka/publish (ADR-015, MIG-176): Core publishes an event for another service through
 * the workspace's own Kafka profile, so the profile's credentials never leave Core. The topic must be
 * on the allow list -- today only Analytics' analytics.query.completed -- so this is not a way to
 * write to any topic at all. Accepted (202) once handed to the producer: best-effort, as the
 * in-process publish was.
 */
@RestController
@RequestMapping("/internal/kafka")
public class InternalKafkaPublishRestApi {

    /** The topics another service may publish through Core. A constant, as the topic itself is. */
    static final Set<String> ALLOWED_TOPICS = Collections.singleton("analytics.query.completed");

    private final Logger logger = LoggerFactory.getLogger(InternalKafkaPublishRestApi.class);
    private final KafkaConnectionResolver resolver;
    private final KafkaTemplateProvider templates;
    private final byte[] token;

    public InternalKafkaPublishRestApi(KafkaConnectionResolver resolver, KafkaTemplateProvider templates,
        @Value("${internal.service-token:}") String token) {
        this.resolver = resolver;
        this.templates = templates;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {tenantId, topic, key, payload}. The tenant is passed, never inferred: the publisher has no principal. */
    @PostMapping(value = "/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> publish(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        String topic = body == null || body.get("topic") == null ? null : body.get("topic").toString();
        if (topic == null || !ALLOWED_TOPICS.contains(topic)) {
            this.logger.warn("Refused to publish on {}: not an allowed topic.", topic);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        Object tenant = body.get("tenantId");
        Long tenantId = tenant instanceof Number ? ((Number) tenant).longValue() : null;
        String key = body.get("key") == null ? null : body.get("key").toString();
        String payload = body.get("payload") == null ? null : body.get("payload").toString();
        // No source task type: the event belongs to a workspace, not a job, so the resolver falls
        // through to the tenant's default profile and then to the platform's.
        KafkaTemplate<String, String> template = this.templates.getTemplate(this.resolver.resolve(tenantId, null));
        template.send(topic, key, payload).addCallback(
            sent -> this.logger.debug("Published an event on {} for workspace {}.", topic, tenantId),
            failed -> this.logger.warn("An event on {} was not published: {}", topic, failed.getMessage()));
        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a Kafka publish without the internal token.");
        }
        return ok;
    }
}
