package process.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Core's consumer of Identity's lifecycle events (MIG-153, MIG-166).
 *
 * People go into user_directory -- a deleted one too, marked deleted, so their old work still shows who did
 * it. A workspace deleted in Identity gets WorkspaceRetirement; every other workspace event changes nothing
 * in Core (a suspended workspace's people cannot sign in, and its admin can be restored).
 *
 * Each listener has its own group and starts from the beginning of the topic, which is how an empty
 * directory fills. An unreadable message is logged and skipped -- no retry makes it readable; a database
 * that cannot be written throws, so the container redelivers. What is still missed, the nightly audits find.
 *
 * @author Nabeel Ahmed
 */
@Component
public class IdentityEventsListener {

    private static final Logger logger = LoggerFactory.getLogger(IdentityEventsListener.class);

    private final ObjectMapper json = new ObjectMapper();
    private final UserDirectory directory;
    private final WorkspaceRetirement retirement;

    public IdentityEventsListener(UserDirectory directory, WorkspaceRetirement retirement) {
        this.directory = directory;
        this.retirement = retirement;
    }

    @KafkaListener(id = "identity-user-directory", topics = IdentityTopics.USER, groupId = "process-user-directory",
        autoStartup = "${identity.events.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onUser(String message) {
        UserDirectory.Entry entry;
        try {
            JsonNode person = this.json.readTree(message).get("payload");
            entry = new UserDirectory.Entry(person.get("appUserId").asLong(), longOrNull(person.get("tenantId")),
                person.get("username").asText(), textOrNull(person.get("fullName")), person.get("status").asText(),
                Instant.parse(person.get("updatedAt").asText()));
        } catch (Exception unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", IdentityTopics.USER, unreadable.getMessage());
            return;
        }
        this.directory.apply(entry);
    }

    @KafkaListener(id = "identity-workspace-lifecycle", topics = IdentityTopics.TENANT, groupId = "process-workspace-lifecycle",
        autoStartup = "${identity.events.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onTenant(String message) {
        String type;
        long tenantId;
        try {
            JsonNode event = this.json.readTree(message);
            type = event.get("eventType").asText();
            JsonNode tenant = event.get("payload").get("tenantId");
            if (tenant == null || !tenant.isIntegralNumber()) {
                throw new IllegalArgumentException("the event names no workspace");
            }
            tenantId = tenant.asLong();
        } catch (Exception unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", IdentityTopics.TENANT, unreadable.getMessage());
            return;
        }
        if ("tenant.deleted".equals(type)) {
            this.retirement.retire(tenantId);
        }
    }

    private static Long longOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
