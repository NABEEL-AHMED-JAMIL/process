package process.notifications.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import java.time.LocalDateTime;

/**
 * One row of notifications_db.notification (MIG-21). A plain row, not a JPA entity: the table lives in
 * Notifications' own database, read and written only through NotificationStore.
 *
 * tenantId is never null in the table. It is the RECIPIENT's tenant -- NotificationStore.PLATFORM_SCOPE
 * for a platform admin, who has none.
 *
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Notification {

    private Long notificationId;

    private Long tenantId;

    private Long recipientUserId;

    private NotificationType type;

    private NotificationSeverity severity;

    private String title;

    private String message;

    private String linkUrl;

    private boolean read;

    private LocalDateTime readAt;

    private LocalDateTime dateCreated;

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public void setRecipientUserId(Long recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    public NotificationType getType() {
        return type;
    }

    public void setType(NotificationType type) {
        this.type = type;
    }

    public NotificationSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(NotificationSeverity severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public void setLinkUrl(String linkUrl) {
        this.linkUrl = linkUrl;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }

}
