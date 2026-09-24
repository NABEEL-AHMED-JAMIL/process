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
 * it. A workspace deleted in Identity gets WorkspaceRetirement. Every workspace event's status also goes into
 * workspace_directory, Core's local view: a Suspended or Inactive workspace's scheduled jobs are paused by the
 * enqueuer until it is Active again (owner decision 2026-09-24), and nothing about the jobs themselves changes.
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
    private final WorkspaceDirectory workspaces;

    public IdentityEventsListener(UserDirectory directory, WorkspaceRetirement retirement, WorkspaceDirectory workspaces) {
        this.directory = directory;
        this.retirement = retirement;
        this.workspaces = workspaces;
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

    /**
     * Every workspace event, into workspace_directory: the status the enqueuer pauses on. A group of its own,
     * from the beginning of the (compacted) topic, so an empty view fills with every workspace's latest status
     * -- a workspace suspended before this listener existed included.
     */
    @KafkaListener(id = "identity-workspace-directory", topics = IdentityTopics.TENANT, groupId = "process-workspace-directory",
        autoStartup = "${identity.events.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onWorkspaceStatus(String message) {
        long tenantId;
        String status;
        Instant updatedAt;
        try {
            JsonNode workspace = this.json.readTree(message).get("payload");
            JsonNode tenant = workspace.get("tenantId");
            if (tenant == null || !tenant.isIntegralNumber()) {
                throw new IllegalArgumentException("the event names no workspace");
            }
            tenantId = tenant.asLong();
            status = workspace.get("status").asText();
            updatedAt = Instant.parse(workspace.get("updatedAt").asText());
        } catch (Exception unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", IdentityTopics.TENANT, unreadable.getMessage());
            return;
        }
        this.workspaces.apply(tenantId, status, updatedAt).ifPresent(change -> {
            if (change.pausedNow()) {
                logger.info("Workspace {} is {} in Identity: its scheduled jobs are paused (each slot skipped) until it is Active again.",
                    tenantId, change.getAfter());
            } else if (change.resumedNow()) {
                logger.info("Workspace {} is {} in Identity: its scheduled jobs resume from their next slot.", tenantId, change.getAfter());
            }
        });
    }

    private static Long longOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
