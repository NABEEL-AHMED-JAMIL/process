package process.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.notifications.contract.ContractViolation;
import org.barco.notifications.contract.JobLifecycleChanged;
import org.barco.notifications.contract.JobLogAppended;
import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.barco.notifications.contract.NotificationTopics;
import org.barco.notifications.contract.Recipients;
import org.barco.platform.event.PlatformEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.identity.OneTimeSecrets;
import process.identity.IdentityPort;
import process.outbox.OutboxWriter;

import java.util.Optional;

/**
 * NotificationPort, as process speaks to Notifications -- its own service since MIG-22. Every call
 * becomes one contract event in platform_outbox, written in the caller's transaction and relayed to
 * Kafka after it commits. Core resolves what the service cannot (the recipient's username and home
 * tenant); attachment bytes are staged and a temporary password is kept by Identity, and both travel
 * by reference.
 *
 * The one non-event left is the old console's /user/queue/reply push (LegacyConsolePush), deleted
 * with that console.
 *
 * @author Nabeel Ahmed
 */
@Component
public class OutboxNotifications implements NotificationPort {

    static final String PRODUCER = "process";
    /** What mailRequested answers once the mail is in the outbox: accepted, not yet sent. */
    public static final String QUEUED = "Mail queued.";

    private final Logger logger = LoggerFactory.getLogger(OutboxNotifications.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OutboxWriter outbox;
    private final IdentityPort users;
    private final OneTimeSecrets secrets;
    private final MailAttachmentStaging staging;
    private final LegacyConsolePush legacyConsole;
    private final UnreadBadges badges;
    /** Mail goes to an emulator (LocalStack) when an endpoint is set: stored, not delivered. */
    private final boolean realInboxes;

    public OutboxNotifications(OutboxWriter outbox, IdentityPort users, OneTimeSecrets secrets,
        MailAttachmentStaging staging, LegacyConsolePush legacyConsole, UnreadBadges badges,
        @Value("${aws.endpoint:}") String awsEndpoint) {
        this.outbox = outbox;
        this.users = users;
        this.secrets = secrets;
        this.staging = staging;
        this.legacyConsole = legacyConsole;
        this.badges = badges;
        this.realInboxes = awsEndpoint == null || awsEndpoint.trim().isEmpty();
    }

    @Override
    public void jobStatusChanged(Long tenantId, JobStatusChanged event) {
        if (event.getRecipientUserId() != null) {
            Optional<IdentityPort.Person> recipient = this.users.person(event.getRecipientUserId());
            if (recipient.isPresent()) {
                // Core resolves the recipient; the service cannot read app_user (contract 1.2.0).
                event.setRecipientUsername(recipient.get().getUsername()).setRecipientTenantId(scopeOf(recipient.get()));
            } else {
                // Gone since the job was assigned. The feed push still goes; only the notice does not.
                event.setRecipientUserId(null).setRecipientUsername(null);
            }
        }
        try {
            this.write(NotificationTopics.JOB_STATUS, event.validatedForDelivery().partitionKey(), tenantId, event);
        } catch (ContractViolation violation) {
            this.logger.warn("Dropped a job status event for job {}: {}", event.getJobId(), violation.getMessage());
        }
    }

    @Override
    public void jobLogAppended(Long tenantId, JobLogAppended line) {
        try {
            this.write(NotificationTopics.JOB_LOG, String.valueOf(line.validated().getJobQueueId()), tenantId, line);
        } catch (ContractViolation violation) {
            this.logger.warn("Dropped a log line for job {}: {}", line.getJobId(), violation.getMessage());
        }
    }

    @Override
    public void jobLifecycleChanged(Long tenantId, JobLifecycleChanged change) {
        try {
            this.write(NotificationTopics.JOB_LIFECYCLE, String.valueOf(change.validated().getJobId()), tenantId, change);
        } catch (ContractViolation violation) {
            this.logger.warn("Dropped a lifecycle event for job {}: {}", change.getJobId(), violation.getMessage());
        }
    }

    @Override
    public void notificationCreated(Long tenantId, NotificationCreated notice) {
        if (notice.getAppUserId() == null) {
            this.logger.debug("A {} notice had no recipient; nothing sent.", notice.getType());
            return;
        }
        Optional<IdentityPort.Person> recipient = this.users.person(notice.getAppUserId());
        if (!recipient.isPresent()) {
            this.logger.info("Not sending a {} notice to app user {}, who does not exist.", notice.getType(), notice.getAppUserId());
            return;
        }
        notice.setRecipientUsername(recipient.get().getUsername()).setRecipientTenantId(scopeOf(recipient.get()));
        try {
            this.write(NotificationTopics.NOTIFICATION_CREATED, String.valueOf(notice.validatedForDelivery().getAppUserId()), tenantId, notice);
        } catch (ContractViolation violation) {
            this.logger.warn("Dropped a {} notice for user {}: {}", notice.getType(), notice.getAppUserId(), violation.getMessage());
        }
    }

    @Override
    public String mailRequested(Long tenantId, MailRequested mail, MailExtras extras) {
        MailExtras carried = extras == null ? MailExtras.NONE : extras;
        try {
            mail.validated();
            if (carried.getAttachment() != null) {
                // Staged, and sent by reference: 20 MiB does not belong on a topic.
                MailRequested.AttachmentRef described = mail.getAttachmentRef();
                mail.setAttachmentRef(this.staging.stage(carried.getAttachment(),
                    described == null ? null : described.getFilename(), described == null ? null : described.getContentType()));
            }
            if (carried.getSecret() != null) {
                // Kept by Identity for one redemption; only the reference travels.
                mail.setSecretRef(this.secrets.keep(carried.getSecret()));
            }
            if (mail.getFailureNotice() != null) {
                // The sender hears about a failure later; resolve who they are now, as for any notice.
                Optional<IdentityPort.Person> sender = this.users.person(mail.getFailureNotice().getAppUserId());
                if (sender.isPresent()) {
                    mail.getFailureNotice().setRecipientUsername(sender.get().getUsername()).setRecipientTenantId(scopeOf(sender.get()));
                } else {
                    mail.setFailureNotice(null);
                }
            }
            this.write(NotificationTopics.MAIL_REQUESTED, mail.validated().partitionKey(), tenantId, mail);
            return QUEUED;
        } catch (ContractViolation violation) {
            this.logger.warn("Refused a {} mail: {}", mail.getTemplate(), violation.getMessage());
            return "Error while Sending Mail";
        } catch (RuntimeException unstaged) {
            // The attachment bucket or the secret store could not take it: nothing was queued.
            this.logger.error("Could not queue a {} mail: {}", mail.getTemplate(), unstaged.getMessage());
            return "Error while Sending Mail";
        }
    }

    /** The recipient's home tenant, or the platform scope for a platform admin -- written, never left out (1.3.0). */
    private static long scopeOf(IdentityPort.Person recipient) {
        return recipient.getTenantId() != null ? recipient.getTenantId() : Recipients.PLATFORM_SCOPE;
    }

    private void write(String topic, String key, Long tenantId, Object payload) {
        PlatformEvent<Object> event = PlatformEvent.of(topic, tenantId, PRODUCER, payload);
        try {
            this.outbox.write(topic, key, event.getEventId(), this.json.writeValueAsString(event));
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("Could not serialise a " + topic + " event", unwritable);
        }
    }

    @Override
    public boolean deliversMailToRealInboxes() {
        return this.realInboxes;
    }

    @Override
    public void forgetRecipient(Long appUserId) {
        this.badges.forget(appUserId);
    }

    @Override
    @Deprecated
    public void legacyOwnerPush(String username, String jobDetailJson) {
        this.legacyConsole.toOwner(username, jobDetailJson);
    }
}
