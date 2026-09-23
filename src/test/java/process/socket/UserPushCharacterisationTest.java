package process.socket;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.messaging.Message;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.security.StompAuthChannelInterceptor;
import process.util.JwtUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part of the Notifications characterisation (MIG-19, MIG-162): the surface as the monolith serves
 * it today, pinned before the service is carved out. The console is not being rewritten, so each
 * case is a frozen contract the extracted service must also pass -- on the simple broker now and
 * on the broker relay that replaces it. A case marked DEFECT records current, wrong behaviour so
 * that its fix shows up as a deliberate change to this file rather than as drift.
 */
class UserPushCharacterisationTest {


    private final WebSocketPresenceService presence = mock(WebSocketPresenceService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    @SuppressWarnings("unchecked")
    private final ClusterBroadcast bridge = new ClusterBroadcast(this.messaging, mock(RedisTemplate.class));

    @Test
    void goesToTheUsersReplyQueueWhenTheyAreOnline() {
        when(this.presence.isOnline("ops@medaxis.example")).thenReturn(true);
        new NotificationService(this.presence, this.bridge).sendNotificationToSpecificUser("ops@medaxis.example", "{}");
        verify(this.messaging).convertAndSendToUser("ops@medaxis.example", "/queue/reply", "{}");
    }

    @Test
    void isNotSentAtAllWhenTheyAreNot() {
        new NotificationService(this.presence, this.bridge).sendNotificationToSpecificUser("ops@medaxis.example", "{}");
        verify(this.messaging, never()).convertAndSendToUser(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }
}
