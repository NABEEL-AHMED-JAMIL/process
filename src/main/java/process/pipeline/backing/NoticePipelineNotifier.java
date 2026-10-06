package process.pipeline.backing;

import org.barco.platform.tenancy.TenantScope;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.notifications.NotificationPort;
import process.notifications.Notices;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Send Notification's notices (MIG-231) through Core's one door to Notifications, the outbox (NotificationPort): one
 * notification-centre notice per recipient, who is a live member of the run's workspace -- the job's owner, its
 * admins, everyone, or people named by id (each checked to be a member; anyone else is skipped).
 *
 * The notice's type is one notifications-service knows (it drops a type it does not): JOB_FAILED for an ERROR notice,
 * JOB_COMPLETED otherwise. A PIPELINE_NOTICE type of its own needs notifications-service's enum first.
 */
@Component
public class NoticePipelineNotifier implements PipelineNotifier {

    private final NotificationPort notifications;
    private final IdentityPort identity;

    public NoticePipelineNotifier(NotificationPort notifications, IdentityPort identity) {
        this.notifications = notifications;
        this.identity = identity;
    }

    @Override
    public int send(Notice notice) {
        List<IdentityPort.Person> members = new ArrayList<>();
        for (IdentityPort.Person person : this.identity.members(TenantScope.tenant(notice.tenantId))) {
            if (!person.isDeleted() && person.getAppUserId() != null && Long.valueOf(notice.tenantId).equals(person.getTenantId())) {
                members.add(person);
            }
        }
        Set<Long> recipients = new LinkedHashSet<>();
        Set<Long> wanted = new HashSet<>();
        if ("owner".equals(notice.to) && notice.ownerUserId != null) {
            wanted.add(notice.ownerUserId);
        } else if ("users".equals(notice.to) && notice.userIds != null) {
            wanted.addAll(notice.userIds);
        }
        for (IdentityPort.Person person : members) {
            boolean chosen = "everyone".equals(notice.to)
                || ("admins".equals(notice.to) && "TENANT_ADMIN".equals(person.getUserRole()))
                || wanted.contains(person.getAppUserId());
            if (chosen && recipients.size() < MAX_RECIPIENTS) {
                recipients.add(person.getAppUserId());
            }
        }
        NotificationSeverity severity = NotificationSeverity.valueOf(notice.severity);
        NotificationType type = severity == NotificationSeverity.ERROR ? NotificationType.JOB_FAILED : NotificationType.JOB_COMPLETED;
        for (Long recipient : recipients) {
            this.notifications.notificationCreated(notice.tenantId, Notices.notice(recipient, type, severity, notice.title, notice.body,
                notice.link));
        }
        return recipients.size();
    }
}
