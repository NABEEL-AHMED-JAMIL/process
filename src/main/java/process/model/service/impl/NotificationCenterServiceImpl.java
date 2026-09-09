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

/**
 * @author Nabeel Ahmed
 * */
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

            Long unreadCount = this.incrementUnread(recipientUserId);

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
        if (updated < 1) {
            // The update is scoped by recipient AND by read = false, so "no rows touched" covers two
            // very different outcomes -- and this used to answer SUCCESS to both. A typo'd id, or one
            // belonging to another user, came back looking exactly like a real mark-as-read, so a
            // caller had no way to tell that nothing had happened. Read the row back to separate them.
            Optional<Notification> existing = this.notificationRepository.findById(notificationId);
            if (existing.isPresent() && existing.get().getRecipientUserId().equals(recipientUserId)) {
                // Own row, already read. The caller asked for the state the row is already in, so this
                // is a no-op and not a business failure -- a second tab replaying a click, or open()
                // firing on a row the list has not refreshed, must not be reported as an error.
                return new ResponseDto(SUCCESS, "Notification already marked as read.");
            }
            // Someone else's notification is reported as missing rather than forbidden: a distinct
            // "not yours" would confirm the id exists to a caller who is not allowed to see the row.
            return new ResponseDto(ERROR, String.format("Notification not found with %d.", notificationId));
        }
        Long remaining = this.redisTemplate.opsForValue().decrement(this.unreadKey(recipientUserId));
        if (remaining != null && remaining < 0) {
            this.redisTemplate.opsForValue().set(this.unreadKey(recipientUserId), "0");
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

    /**
     * Drops a user's cached unread counter. Meant to be called when an app user is deleted.
     *
     * The counter is keyed by app user id and nothing ever removed it, so every deleted user left a
     * key behind permanently: the deployment this was found in held 23 notif:unread keys against 6
     * app_user rows, one of those already Status.Delete. The keyspace only ever grew.
     *
     * A TTL is the obvious alternative and is the wrong fix here, because create() bumps the badge
     * with INCR and INCR on a missing key answers 1 rather than forcing a recount. Expiring the key
     * under a live user would therefore not make the next read fall back to the database -- the next
     * notification would republish the badge as "1 unread" over a mailbox holding 49, and
     * unreadCount() would then serve that 1 straight back out of the cache until the user marked
     * something read. Removing the key when the user goes away has no live badge to corrupt.
     */
    public void clearUnreadCount(Long recipientUserId) {
        if (recipientUserId == null) {
            return;
        }
        this.redisTemplate.delete(this.unreadKey(recipientUserId));
        this.logger.debug("Cleared cached unread counter for app user {}.", recipientUserId);
    }

    /**
     * Bumps the cached unread counter for a newly created notification, seeding it from the database
     * when the key is absent instead of letting INCR materialise it at 1.
     *
     * A plain INCR was survivable only while nothing ever removed a key. clearUnreadCount now can,
     * and changeUserStatus allows Delete -> Active, so the sequence "delete a user with 48 unread,
     * reactivate them, send them one notification" would have announced 1 unread over 49 rows and
     * cached that wrong number. Recounting on a miss keeps the badge honest whatever removed the key.
     */
    private Long incrementUnread(Long recipientUserId) {
        String key = this.unreadKey(recipientUserId);
        if (Boolean.TRUE.equals(this.redisTemplate.hasKey(key))) {
            return this.redisTemplate.opsForValue().increment(key);
        }
        // The caller has already flushed the new notification, so this count includes it.
        long unreadCount = this.notificationRepository.countByRecipientUserIdAndReadFalse(recipientUserId);
        // setIfAbsent rather than set: a concurrent create() may have seeded and incremented the key
        // since the miss above, and overwriting would drop its notification off the badge.
        if (Boolean.TRUE.equals(this.redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(unreadCount)))) {
            return unreadCount;
        }
        return this.redisTemplate.opsForValue().increment(key);
    }

    private String unreadKey(Long recipientUserId) {
        return UNREAD_COUNT_KEY_PREFIX + recipientUserId;
    }

}
