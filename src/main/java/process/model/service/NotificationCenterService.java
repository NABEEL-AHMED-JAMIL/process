package process.model.service;

import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;

/**
 * @author Nabeel Ahmed
 * */
public interface NotificationCenterService {

    void create(Long tenantId, Long recipientUserId, NotificationType type, NotificationSeverity severity,
        String title, String message, String linkUrl);

    ResponseDto list(Boolean unreadOnly, Long page, Long limit) throws Exception;

    ResponseDto unreadCount() throws Exception;

    ResponseDto markRead(Long notificationId) throws Exception;

    ResponseDto markAllRead() throws Exception;

    /**
     * Drops the cached unread counter for one user.
     *
     * On the interface, not just the implementation: every consumer here autowires the
     * interface, so a method that exists only on the impl is unreachable and the Redis keys it
     * was written to clean up would go on accumulating for ever -- which is the defect, not
     * the fix. Called when an account stops being able to read its notifications.
     */
    void clearUnreadCount(Long recipientUserId);

}
