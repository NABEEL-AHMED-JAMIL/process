package process.model.service;

import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;

public interface NotificationCenterService {

    void create(Long tenantId, Long recipientUserId, NotificationType type, NotificationSeverity severity,
        String title, String message, String linkUrl);

    ResponseDto list(Boolean unreadOnly, Long page, Long limit) throws Exception;

    ResponseDto unreadCount() throws Exception;

    ResponseDto markRead(Long notificationId) throws Exception;

    ResponseDto markAllRead() throws Exception;

}
