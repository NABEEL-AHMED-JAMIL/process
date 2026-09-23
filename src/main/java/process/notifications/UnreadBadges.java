package process.notifications;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops a deleted user's cached unread badge (notif:unread:{appUserId}), which notifications-service
 * keeps. Nothing else removes it, and a TTL cannot stand in: the badge is rebuilt with INCR, so an
 * expired key under a live user would come back as "1 unread" (see the service's NotificationCenter).
 *
 * @author Nabeel Ahmed
 */
@Component
public class UnreadBadges {

    static final String PREFIX = "notif:unread:";

    private final RedisTemplate<String, String> redis;

    public UnreadBadges(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public void forget(Long appUserId) {
        if (appUserId != null) {
            this.redis.delete(PREFIX + appUserId);
        }
    }
}
