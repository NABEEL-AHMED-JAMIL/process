package process.notifications;

import org.barco.notifications.contract.NotificationCreated;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;

/** Builds a notification-centre notice in the contract's shape from the enums process already uses. */
public final class Notices {

    private Notices() {
    }

    public static NotificationCreated notice(Long recipientUserId, NotificationType type, NotificationSeverity severity,
        String title, String body, String link) {
        return new NotificationCreated().setAppUserId(recipientUserId).setType(type.name())
            .setSeverity(severity.name()).setTitle(title).setBody(body).setLink(link);
    }
}
