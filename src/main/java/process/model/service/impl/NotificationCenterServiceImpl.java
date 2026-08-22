package process.model.service.impl;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.NotificationDto;
import process.model.dto.PagingDto;
import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.pojo.AppUser;
import process.model.pojo.Notification;
import process.model.repository.AppUserRepository;
import process.model.repository.NotificationRepository;
import process.model.service.NotificationCenterService;
import process.security.TenantContext;
import process.util.PagingUtil;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

@Service
public class NotificationCenterServiceImpl implements NotificationCenterService {

    private final Logger logger = LoggerFactory.getLogger(NotificationCenterServiceImpl.class);

    private static final String UNREAD_COUNT_KEY_PREFIX = "notif:unread:";

    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationCenterServiceImpl(NotificationRepository notificationRepository,
        AppUserRepository appUserRepository,
        RedisTemplate<String, String> redisTemplate,
        SimpMessagingTemplate messagingTemplate) {
        this.notificationRepository = notificationRepository;
        this.appUserRepository = appUserRepository;
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(Long tenantId, Long recipientUserId, NotificationType type, NotificationSeverity severity,
        String title, String message, String linkUrl) {
        if (recipientUserId == null) {
            this.logger.debug("Notification.create called with no recipient -- nothing to send ({}).", type);
            return;
        }
        try {
            Notification notification = new Notification();
            notification.setTenantId(tenantId);
            notification.setRecipientUserId(recipientUserId);
            notification.setType(type);
            notification.setSeverity(severity);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setLinkUrl(linkUrl);
            notification.setRead(false);
            notification.setDateCreated(LocalDateTime.now());
            this.notificationRepository.saveAndFlush(notification);

            Long unreadCount = this.redisTemplate.opsForValue().increment(this.unreadKey(recipientUserId));

            Optional<AppUser> recipient = this.appUserRepository.findById(recipientUserId);
            if (recipient.isPresent()) {
                Map<String, Object> notificationJson = new HashMap<>();
                notificationJson.put("notificationId", notification.getNotificationId());
                notificationJson.put("type", notification.getType().name());
                notificationJson.put("severity", notification.getSeverity().name());
                notificationJson.put("title", notification.getTitle());
                notificationJson.put("message", notification.getMessage());
                notificationJson.put("linkUrl", notification.getLinkUrl());
                notificationJson.put("read", notification.isRead());
                notificationJson.put("dateCreated", notification.getDateCreated().toString());

                Map<String, Object> payload = new HashMap<>();
                payload.put("notification", notificationJson);
                payload.put("unreadCount", unreadCount);
                this.messagingTemplate.convertAndSendToUser(recipient.get().getUsername(), "/queue/notifications", new Gson().toJson(payload));
            }
        } catch (Exception ex) {
            this.logger.error("Failed to create notification ({}) for recipient {}.", type, recipientUserId, ex);
        }
    }

    @Override
    public ResponseDto list(Boolean unreadOnly, Long page, Long limit) throws Exception {
        Long recipientUserId = TenantContext.getAppUserId();
        Pageable paging = PagingUtil.ApplyPaging("dateCreated", "desc", page, limit);
        Page<Notification> result = Boolean.TRUE.equals(unreadOnly)
            ? this.notificationRepository.findByRecipientUserIdAndReadOrderByDateCreatedDesc(recipientUserId, false, paging)
            : this.notificationRepository.findByRecipientUserIdOrderByDateCreatedDesc(recipientUserId, paging);
        List<NotificationDto> notifications = result.getContent().stream()
            .map(NotificationDto::from)
            .collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Notifications fetched.", notifications,
            PagingUtil.convertEntityToPagingDTO(result.getTotalElements(), paging));
    }

    @Override
    public ResponseDto unreadCount() throws Exception {
        Long recipientUserId = TenantContext.getAppUserId();
        String cached = this.redisTemplate.opsForValue().get(this.unreadKey(recipientUserId));
        long unreadCount;
        if (cached != null) {
            unreadCount = Long.parseLong(cached);
        } else {
            unreadCount = this.notificationRepository.countByRecipientUserIdAndReadFalse(recipientUserId);
            this.redisTemplate.opsForValue().set(this.unreadKey(recipientUserId), String.valueOf(unreadCount));
        }
        return new ResponseDto(SUCCESS, "Unread count fetched.", unreadCount);
    }

    @Override
    @Transactional
    public ResponseDto markRead(Long notificationId) throws Exception {
        if (notificationId == null) {
            return new ResponseDto(ERROR, "notificationId missing.");
        }
        Long recipientUserId = TenantContext.getAppUserId();
        int updated = this.notificationRepository.markRead(notificationId, LocalDateTime.now(), recipientUserId);
        if (updated > 0) {
            Long remaining = this.redisTemplate.opsForValue().decrement(this.unreadKey(recipientUserId));
            if (remaining != null && remaining < 0) {
                this.redisTemplate.opsForValue().set(this.unreadKey(recipientUserId), "0");
            }
        }
        return new ResponseDto(SUCCESS, "Marked as read.");
    }

    @Override
    @Transactional
    public ResponseDto markAllRead() throws Exception {
        Long recipientUserId = TenantContext.getAppUserId();
        this.notificationRepository.markAllRead(recipientUserId, LocalDateTime.now());
        this.redisTemplate.opsForValue().set(this.unreadKey(recipientUserId), "0");
        return new ResponseDto(SUCCESS, "All notifications marked as read.");
    }

    private String unreadKey(Long recipientUserId) {
        return UNREAD_COUNT_KEY_PREFIX + recipientUserId;
    }

}
