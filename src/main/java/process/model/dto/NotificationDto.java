package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.pojo.Notification;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long notificationId;
    private NotificationType type;
    private NotificationSeverity severity;
    private String title;
    private String message;
    private String linkUrl;
    private boolean read;
    private LocalDateTime dateCreated;

    public NotificationDto() {}

    public static NotificationDto from(Notification notification) {
        NotificationDto dto = new NotificationDto();
        dto.setNotificationId(notification.getNotificationId());
        dto.setType(notification.getType());
        dto.setSeverity(notification.getSeverity());
        dto.setTitle(notification.getTitle());
        dto.setMessage(notification.getMessage());
        dto.setLinkUrl(notification.getLinkUrl());
        dto.setRead(notification.isRead());
        dto.setDateCreated(notification.getDateCreated());
        return dto;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
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

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
