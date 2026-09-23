package process.notifications;

import org.barco.notifications.contract.JobLifecycleChanged;
import org.barco.notifications.contract.JobLogAppended;
import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;

/**
 * The one door from the rest of process into Notifications (MIG-20).
 *
 * Every live push, notification-centre row and mail goes through here, in the shape of the
 * notifications-contract events (MIG-191) -- so when Notifications leaves this process, what changes
 * is the implementation behind this interface (an outbox and the platform bus instead of
 * {@link InProcessNotifications}), not the thirty-odd places that call it. A test holds the edge:
 * nothing outside the Notifications packages may reach the socket or mailer classes directly.
 *
 * <b>Core decides, Notifications delivers.</b> Callers compute {@code isNewTransition}, resolve the
 * recipient and choose whether a mail is wanted; the implementation never re-derives any of it.
 *
 * <b>Never fails its caller.</b> A push nobody receives or a mail that does not go must never fail
 * the operation that triggered it; implementations catch and log.
 *
 * @author Nabeel Ahmed
 */
public interface NotificationPort {

    /**
     * A run's status, for the tenant's live job feed -- published after the surrounding transaction
     * commits. When the event {@code raisesOutcome()} (a NEW Completed or Failed) it also raises the
     * owner's notice, once per (run, attempt, status).
     */
    void jobStatusChanged(Long tenantId, JobStatusChanged event);

    /** One log line of a run, for an open run-logs screen. */
    void jobLogAppended(Long tenantId, JobLogAppended line);

    /** A job created, edited, deleted or toggled -- published after commit; the console re-reads the row. */
    void jobLifecycleChanged(Long tenantId, JobLifecycleChanged change);

    /** A notification-centre row for one person. A notice with no recipient is quietly not sent. */
    void notificationCreated(Long tenantId, NotificationCreated notice);

    /**
     * Render one of Notifications' templates and send it. {@code extras} carries what cannot travel
     * in the event while Notifications is in this process: attachment bytes (by reference once it
     * leaves -- MIG-22) and a welcome mail's temporary password (a secretRef redeemed with Identity).
     * Returns the mailer's own answer, which the file-share and welcome flows report to the person.
     */
    String mailRequested(Long tenantId, MailRequested mail, MailExtras extras);

    /** False while mail goes to an emulator that stores and never delivers (LocalStack). */
    boolean deliversMailToRealInboxes();

    /** A deleted user's cached unread counter, dropped (see NotificationCenterServiceImpl). */
    void forgetRecipient(Long appUserId);

    /**
     * LEGACY: the old webpack console's per-user /user/queue/reply push. The current console never
     * subscribes to it. Removed together with that console (decided 2026-09-23; blocked until the
     * new console has Agents and Ollama screens).
     */
    @Deprecated
    void legacyOwnerPush(String username, String jobDetailJson);
}
