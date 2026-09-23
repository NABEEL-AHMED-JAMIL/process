package process.notifications;

import org.barco.notifications.contract.ContractViolation;
import org.barco.notifications.contract.JobLifecycleChanged;
import org.barco.notifications.contract.JobLogAppended;
import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import process.emailer.EmailMessagesFactory;
import process.emailer.TemplateType;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.service.NotificationCenterService;
import process.socket.JobEventPublisher;
import process.socket.NotificationService;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notifications, while it still lives in this process: the port's implementation, delegating to the
 * socket publisher, the notification centre and the mailer exactly as their callers used to.
 *
 * This class is the future Notifications service's inbound side. When the service is split (MIG-22)
 * the port's implementation in process becomes an outbox writer, and this logic moves behind a
 * consumer of the same contract events -- which is why it reads only the event, never source_job.
 *
 * Two things it adds, both from the contract rather than invented here:
 * <ul>
 *   <li>An outcome notice and a job mail are sent once per (run, attempt, status), recorded in Redis
 *       for 24 hours. A worker that reports Failed twice for one attempt used to get two failure
 *       mails; nothing on the callback path said the outcome had already been announced.</li>
 *   <li>A payload that breaks the contract is logged and dropped, never thrown: a push nobody
 *       receives must never fail the operation that triggered it.</li>
 * </ul>
 *
 * @author Nabeel Ahmed
 */
@Component
public class InProcessNotifications implements NotificationPort {

    private static final Logger logger = LoggerFactory.getLogger(InProcessNotifications.class);

    static final String ONCE_KEY_PREFIX = "notif:once:";
    static final Duration ONCE_WINDOW = Duration.ofHours(24);
    /** Where a job notice sends the person -- unchanged from BulkAction.notifyJobOutcome. */
    static final String JOB_NOTICE_LINK = "/jobList";

    private final JobEventPublisher jobEvents;
    private final NotificationService userPush;
    private final NotificationCenterService notificationCenter;
    private final EmailMessagesFactory mailer;
    private final RedisTemplate<String, String> redis;

    public InProcessNotifications(JobEventPublisher jobEvents, NotificationService userPush,
        NotificationCenterService notificationCenter, EmailMessagesFactory mailer,
        // Named: Spring Boot also defines stringRedisTemplate, and the two are otherwise indistinguishable.
        @Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.jobEvents = jobEvents;
        this.userPush = userPush;
        this.notificationCenter = notificationCenter;
        this.mailer = mailer;
        this.redis = redis;
    }

    @Override
    public void jobStatusChanged(Long tenantId, JobStatusChanged event) {
        try {
            event.validated();
            this.jobEvents.publishStatusAfterCommit(tenantId, event.getJobId(), event.getJobQueueId(),
                event.getJobRunningStatus(), event.getMessage());
            if (event.raisesOutcome() && event.getRecipientUserId() != null && this.once("notice:" + event.outcomeKey())) {
                this.raiseOutcomeNotice(tenantId, event);
            }
        } catch (ContractViolation violation) {
            logger.warn("Dropped a job status event for job {}: {}", event.getJobId(), violation.getMessage());
        }
    }

    /** The owner's notice, worded exactly as BulkAction.notifyJobOutcome worded it. */
    private void raiseOutcomeNotice(Long tenantId, JobStatusChanged event) {
        String jobName = event.getJobName() != null ? event.getJobName() : "Job " + event.getJobId();
        if ("Completed".equals(event.getJobRunningStatus())) {
            this.notificationCenter.create(tenantId, event.getRecipientUserId(), NotificationType.JOB_COMPLETED,
                NotificationSeverity.SUCCESS, "Job completed", jobName + " finished successfully.", JOB_NOTICE_LINK);
        } else {
            this.notificationCenter.create(tenantId, event.getRecipientUserId(), NotificationType.JOB_FAILED,
                NotificationSeverity.ERROR, "Job failed", jobName + " failed.", JOB_NOTICE_LINK);
        }
    }

    @Override
    public void jobLogAppended(Long tenantId, JobLogAppended line) {
        try {
            line.validated();
            this.jobEvents.publishLog(tenantId, line.getJobId(), line.getJobQueueId(), line.getMessage());
        } catch (ContractViolation violation) {
            logger.warn("Dropped a log line for job {}: {}", line.getJobId(), violation.getMessage());
        }
    }

    @Override
    public void jobLifecycleChanged(Long tenantId, JobLifecycleChanged change) {
        try {
            change.validated();
            this.jobEvents.publishChangedAfterCommit(tenantId, change.getJobId(), change.getChange().socketType());
        } catch (ContractViolation violation) {
            logger.warn("Dropped a lifecycle event for job {}: {}", change.getJobId(), violation.getMessage());
        }
    }

    @Override
    public void notificationCreated(Long tenantId, NotificationCreated notice) {
        if (notice.getAppUserId() == null) {
            // As NotificationCenterServiceImpl.create always did: no recipient, nothing to send.
            logger.debug("A {} notice had no recipient; nothing sent.", notice.getType());
            return;
        }
        try {
            notice.validated();
            this.notificationCenter.create(tenantId, notice.getAppUserId(),
                NotificationType.valueOf(notice.getType()), NotificationSeverity.valueOf(notice.getSeverity()),
                notice.getTitle(), notice.getBody(), notice.getLink());
        } catch (IllegalArgumentException refused) {
            // A ContractViolation, or a type or severity this process has no enum value for.
            logger.warn("Dropped a {} notice for user {}: {}", notice.getType(), notice.getAppUserId(), refused.getMessage());
        }
    }

    @Override
    public String mailRequested(Long tenantId, MailRequested mail, MailExtras extras) {
        MailExtras carried = extras == null ? MailExtras.NONE : extras;
        try {
            mail.validated();
        } catch (ContractViolation violation) {
            logger.warn("Refused a {} mail: {}", mail.getTemplate(), violation.getMessage());
            return "Error while Sending Mail";
        }
        if (mail.getDedupeKey() != null && !this.once("mail:" + mail.getDedupeKey())) {
            logger.info("A {} mail for {} was already sent; not sending it again.", mail.getTemplate(), mail.getDedupeKey());
            return "Mail already sent.";
        }
        Map<String, Object> body = new LinkedHashMap<>(mail.getBodyMap());
        if (carried.getSecret() != null) {
            // The welcome templates print it as $request.temporary_password. It is added here, at the
            // last moment, and never travels in the event.
            body.put("temporary_password", carried.getSecret());
        }
        MailRequested.AttachmentRef attachment = mail.getAttachmentRef();
        return this.mailer.sendTemplate(TemplateType.valueOf(mail.getTemplate().name()), mail.getRecipient(),
            mail.getCc(), mail.getSubject(), body, carried.getAttachment(),
            attachment == null ? null : attachment.getFilename(), attachment == null ? null : attachment.getContentType());
    }

    @Override
    public boolean deliversMailToRealInboxes() {
        return this.mailer.deliversToRealInboxes();
    }

    @Override
    public void forgetRecipient(Long appUserId) {
        this.notificationCenter.clearUnreadCount(appUserId);
    }

    @Override
    @Deprecated
    public void legacyOwnerPush(String username, String jobDetailJson) {
        this.userPush.sendNotificationToSpecificUser(username, jobDetailJson);
    }

    /**
     * True the first time a key is seen within the window. Notices and mails keep separate keys
     * ("notice:" and "mail:") because one outcome raises both, under the same (run, attempt, status). Fails open: if Redis cannot answer, the
     * notice or mail goes out -- a rare duplicate is better than a failure nobody hears about.
     */
    private boolean once(String key) {
        try {
            Boolean first = this.redis.opsForValue().setIfAbsent(ONCE_KEY_PREFIX + key, "1", ONCE_WINDOW);
            return !Boolean.FALSE.equals(first);
        } catch (RuntimeException redisDown) {
            logger.warn("Could not check {} for a repeat ({}); sending it.", key, redisDown.getMessage());
            return true;
        }
    }
}
