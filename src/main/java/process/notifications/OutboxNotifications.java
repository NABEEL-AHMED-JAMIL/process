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
import org.barco.platform.event.PlatformEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.outbox.OutboxWriter;

import java.util.Optional;

/**
 * NotificationPort for a Notifications that runs as its own service (MIG-22): every call becomes
 * one contract event in platform_outbox, written in the caller's transaction and relayed to Kafka
 * after it commits. Selected by notifications.transport=outbox; InProcessNotifications stays the
 * port otherwise.
 *
 * Three things still go through the in-process side, each for a stated reason:
 * <ul>
 *   <li>a mail carrying attachment bytes or a temporary password -- neither may ride on a topic,
 *       and until they travel as attachmentRef and secretRef they are sent from here;</li>
 *   <li>the old console's /user/queue/reply push, which ClusterBroadcast already carries to
 *       whichever instance holds the session;</li>
 *   <li>the two synchronous questions, which are not events.</li>
 * </ul>
 *
 * @author Nabeel Ahmed
 */
@Primary
@Component
@ConditionalOnProperty(name = "notifications.transport", havingValue = "outbox")
public class OutboxNotifications implements NotificationPort {

    static final String PRODUCER = "process";
    static final String QUEUED = "Mail queued.";

    private final Logger logger = LoggerFactory.getLogger(OutboxNotifications.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OutboxWriter outbox;
    private final InProcessNotifications inProcess;
    private final AppUserRepository users;

    public OutboxNotifications(OutboxWriter outbox, InProcessNotifications inProcess, AppUserRepository users) {
        this.outbox = outbox;
        this.inProcess = inProcess;
        this.users = users;
    }

    @Override
    public void jobStatusChanged(Long tenantId, JobStatusChanged event) {
        if (event.getRecipientUserId() != null) {
            Optional<AppUser> recipient = this.users.findById(event.getRecipientUserId());
            if (recipient.isPresent()) {
                // Core resolves the recipient; the service cannot read app_user (contract 1.2.0).
                event.setRecipientUsername(recipient.get().getUsername()).setRecipientTenantId(recipient.get().getTenantId());
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
        Optional<AppUser> recipient = this.users.findById(notice.getAppUserId());
        if (!recipient.isPresent()) {
            this.logger.info("Not sending a {} notice to app user {}, who does not exist.", notice.getType(), notice.getAppUserId());
            return;
        }
        notice.setRecipientUsername(recipient.get().getUsername()).setRecipientTenantId(recipient.get().getTenantId());
        try {
            this.write(NotificationTopics.NOTIFICATION_CREATED, String.valueOf(notice.validatedForDelivery().getAppUserId()), tenantId, notice);
        } catch (ContractViolation violation) {
            this.logger.warn("Dropped a {} notice for user {}: {}", notice.getType(), notice.getAppUserId(), violation.getMessage());
        }
    }

    @Override
    public String mailRequested(Long tenantId, MailRequested mail, MailExtras extras) {
        if (extras != null && (extras.getAttachment() != null || extras.getSecret() != null)) {
            return this.inProcess.mailRequested(tenantId, mail, extras);
        }
        try {
            this.write(NotificationTopics.MAIL_REQUESTED, mail.validated().partitionKey(), tenantId, mail);
            return QUEUED;
        } catch (ContractViolation violation) {
            this.logger.warn("Refused a {} mail: {}", mail.getTemplate(), violation.getMessage());
            return "Error while Sending Mail";
        }
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
        return this.inProcess.deliversMailToRealInboxes();
    }

    @Override
    public void forgetRecipient(Long appUserId) {
        this.inProcess.forgetRecipient(appUserId);
    }

    @Override
    @Deprecated
    public void legacyOwnerPush(String username, String jobDetailJson) {
        this.inProcess.legacyOwnerPush(username, jobDetailJson);
    }
}
