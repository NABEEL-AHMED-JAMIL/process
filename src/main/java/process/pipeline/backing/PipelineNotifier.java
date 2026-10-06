package process.pipeline.backing;

import java.util.List;

/**
 * Notices a pipeline step sends (MIG-231: Send Notification), through Core's own path to notifications-service: the
 * outbox (NotificationPort), one notification-centre notice per recipient.
 */
public interface PipelineNotifier {

    /** At most this many people get one step's notice. */
    int MAX_RECIPIENTS = 500;

    /** How many notices were sent. */
    int send(Notice notice) throws Exception;

    final class Notice {
        public long tenantId;
        /** owner, admins or everyone -- or users, with {@link #userIds}. */
        public String to;
        public Long ownerUserId;
        public List<Long> userIds;
        /** INFO, SUCCESS, WARNING or ERROR. */
        public String severity;
        public String title;
        public String body;
        public String link;
    }
}
