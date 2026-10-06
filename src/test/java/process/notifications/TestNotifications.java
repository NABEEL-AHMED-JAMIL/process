package process.notifications;

import org.barco.notifications.contract.ContractViolation;
import org.barco.notifications.contract.JobEvent;
import org.barco.notifications.contract.JobLifecycleChanged;
import org.barco.notifications.contract.JobLogAppended;
import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * A NotificationPort for tests of the code that raises notifications (MIG-22 part 5).
 *
 * Process delivers nothing any more: what it owes is the right port call. This records each call
 * onto a small sink a test can verify, in the shape the old in-process collaborators had -- a
 * notice's tenant, recipient, type, severity, title, body and link; a feed event; a mail's
 * template, recipient and body -- so each test still reads as what the user gets. How any of it is
 * delivered is notifications-service's to test, and it does.
 *
 * Pass null for any sink a test does not care about.
 */
public final class TestNotifications {

    /** A notice raised through notificationCreated. */
    public interface NoticeSink {
        void create(Long tenantId, Long recipientUserId, NotificationType type, NotificationSeverity severity,
            String title, String message, String linkUrl);
    }

    /** The live jobs feed: jobStatusChanged, jobLifecycleChanged and jobLogAppended. */
    public interface FeedSink {
        void publishStatusAfterCommit(Long tenantId, Long jobId, Long jobQueueId, String runningStatus, String message);

        void publishChangedAfterCommit(Long tenantId, Long jobId, JobEvent change);

        void publishLog(Long tenantId, Long jobId, Long jobQueueId, String message);
    }

    /** A mail request, with a temporary password (MailExtras.secret) where the template prints it. */
    public interface MailSink {
        String sendTemplate(MailRequested.Template template, String recipient, List<String> cc, String subject,
            Map<String, Object> bodyMap, byte[] attachment, String attachmentFilename, String attachmentContentType);
    }

    private TestNotifications() {
    }

    public static NotificationPort recording(FeedSink feed, NoticeSink notices, MailSink mail) {
        return new Recording(feed != null ? feed : mock(FeedSink.class),
            notices != null ? notices : mock(NoticeSink.class), mail != null ? mail : mock(MailSink.class));
    }

    private static final class Recording implements NotificationPort {

        private final FeedSink feed;
        private final NoticeSink notices;
        private final MailSink mail;

        Recording(FeedSink feed, NoticeSink notices, MailSink mail) {
            this.feed = feed;
            this.notices = notices;
            this.mail = mail;
        }

        /** As the outbox adapter: a payload that breaks the contract is dropped, never thrown. */
        @Override
        public void jobStatusChanged(Long tenantId, JobStatusChanged event) {
            try {
                event.validated();
            } catch (ContractViolation dropped) {
                return;
            }
            this.feed.publishStatusAfterCommit(tenantId, event.getJobId(), event.getJobQueueId(), event.getJobRunningStatus(), event.getMessage());
        }

        @Override
        public void jobLogAppended(Long tenantId, JobLogAppended line) {
            try {
                line.validated();
            } catch (ContractViolation dropped) {
                return;
            }
            this.feed.publishLog(tenantId, line.getJobId(), line.getJobQueueId(), line.getMessage());
        }

        @Override
        public void jobLifecycleChanged(Long tenantId, JobLifecycleChanged change) {
            try {
                change.validated();
            } catch (ContractViolation dropped) {
                return;
            }
            this.feed.publishChangedAfterCommit(tenantId, change.getJobId(), change.getChange().event());
        }

        @Override
        public void notificationCreated(Long tenantId, NotificationCreated notice) {
            if (notice.getAppUserId() == null) {
                return;
            }
            try {
                notice.validated();
                this.notices.create(tenantId, notice.getAppUserId(), NotificationType.valueOf(notice.getType()),
                    NotificationSeverity.valueOf(notice.getSeverity()), notice.getTitle(), notice.getBody(), notice.getLink());
            } catch (IllegalArgumentException dropped) {
                // A ContractViolation, or a type this process has no enum value for.
            }
        }

        @Override
        public String mailRequested(Long tenantId, MailRequested request, MailExtras extras) {
            MailExtras carried = extras == null ? MailExtras.NONE : extras;
            try {
                request.validated();
            } catch (ContractViolation refused) {
                return "Error while Sending Mail";
            }
            Map<String, Object> body = new LinkedHashMap<>(request.getBodyMap());
            if (carried.getSecret() != null) {
                body.put("temporary_password", carried.getSecret());
            }
            MailRequested.AttachmentRef ref = request.getAttachmentRef();
            return this.mail.sendTemplate(request.getTemplate(), request.getRecipient(), request.getCc(), request.getSubject(),
                body, carried.getAttachment(), ref == null ? null : ref.getFilename(), ref == null ? null : ref.getContentType());
        }

        @Override
        public boolean deliversMailToRealInboxes() {
            return false;
        }

        @Override
        public void forgetRecipient(Long appUserId) {
        }
    }
}
