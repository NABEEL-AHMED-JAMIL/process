package process.directory;

import org.barco.platform.event.PlatformEvent;
import org.barco.platform.identity.IdentityEvents;
import org.barco.platform.identity.IdentityTopics;
import org.barco.platform.identity.TenantLifecycle;
import org.barco.platform.identity.UserLifecycle;
import org.barco.platform.tenancy.AcrossTenants;
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
@AcrossTenants("Identity's people and workspace feeds span every workspace: a person moves between them, a platform "
    + "administrator has none, and a deleted workspace's jobs are retired")
public class IdentityEventsListener {

    private static final Logger logger = LoggerFactory.getLogger(IdentityEventsListener.class);

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
            // MIG-304: the one reader of Identity's events, platform-commons' -- the same checks in every consumer.
            UserLifecycle person = IdentityEvents.user(message).getPayload();
            entry = new UserDirectory.Entry(person.getAppUserId(), person.getTenantId(), person.getUsername(), person.getFullName(),
                person.getStatus(), person.updatedAtInstant());
        } catch (IllegalArgumentException unreadable) {
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
        Instant updatedAt;
        try {
            PlatformEvent<TenantLifecycle> event = IdentityEvents.tenant(message);
            type = event.getEventType();
            tenantId = event.getPayload().getTenantId();
            updatedAt = event.getPayload().updatedAtInstant();
        } catch (IllegalArgumentException unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", IdentityTopics.TENANT, unreadable.getMessage());
            return;
        }
        if (TenantLifecycle.DELETED.equals(type)) {
            // Not when the workspace has been restored since (a replay of the compacted topic, event audit E8).
            this.retirement.retire(tenantId, updatedAt);
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
            TenantLifecycle workspace = IdentityEvents.tenant(message).getPayload();
            if (workspace.getStatus() == null || workspace.getUpdatedAt() == null) {
                throw new IllegalArgumentException("workspace " + workspace.getTenantId() + "'s event has no status or updatedAt");
            }
            tenantId = workspace.getTenantId();
            status = workspace.getStatus();
            updatedAt = workspace.updatedAtInstant();
        } catch (IllegalArgumentException unreadable) {
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
}
