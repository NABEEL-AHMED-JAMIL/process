package process.socket;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.messaging.Message;
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
class PresenceCharacterisationTest {


    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    private WebSocketPresenceService presence;

    @BeforeEach
    void setUp() {
        when(this.redis.opsForSet()).thenReturn(this.sets);
        this.presence = new WebSocketPresenceService(this.redis);
    }

    @Test
    void aSessionIsRecordedUnderTheUsersKeyWithATwelveHourSafetyTtl() {
        this.presence.markOnline("ops@medaxis.example", "s-1");
        verify(this.sets).add("ws:online:ops@medaxis.example", "s-1");
        verify(this.redis).expire("ws:online:ops@medaxis.example", Duration.ofHours(12));
    }

    @Test
    void aUserIsOnlineWhileAnySessionRemains() {
        when(this.sets.size("ws:online:ops@medaxis.example")).thenReturn(2L, 0L);
        assertThat(this.presence.isOnline("ops@medaxis.example")).isTrue();
        assertThat(this.presence.isOnline("ops@medaxis.example")).isFalse();
    }

    @Test
    void goingOfflineRemovesOnlyThatSession() {
        this.presence.markOffline("ops@medaxis.example", "s-1");
        verify(this.sets).remove("ws:online:ops@medaxis.example", "s-1");
    }
}
