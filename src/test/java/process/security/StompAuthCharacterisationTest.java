package process.security;

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
class StompAuthCharacterisationTest {

    private static final long TENANT_A = 2901L;
    private static final long TENANT_B = 2905L;

    private JwtUtil jwtUtil;

    @BeforeEach
    void realTokens() {
        this.jwtUtil = new JwtUtil();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 3);
        ReflectionTestUtils.setField(this.jwtUtil, "base64Key", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.setField(this.jwtUtil, "accessTokenExpiryMinutes", 30L);
        ReflectionTestUtils.setField(this.jwtUtil, "refreshTokenExpiryDays", 7L);
    }

    private String access(UserRole role, Long tenantId) {
        return "Bearer " + this.jwtUtil.generateAccessToken(user(role, tenantId));
    }

    private String refresh(UserRole role, Long tenantId) {
        return "Bearer " + this.jwtUtil.generateRefreshToken(user(role, tenantId));
    }

    private static AppUser user(UserRole role, Long tenantId) {
        AppUser user = new AppUser();
        user.setAppUserId(10L);
        user.setUsername("ops@medaxis.example");
        user.setTenantId(tenantId);
        user.setUserRole(role);
        return user;
    }

    private static Message<byte[]> frame(StompCommand command, String destination, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) accessor.setDestination(destination);
        if (authorization != null) accessor.addNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // ---- the seven SUBSCRIBE outcomes (plus a tampered token) -----------------------------------



    private boolean allowed(String destination, String authorization) {
        return new StompAuthChannelInterceptor(this.jwtUtil).preSend(frame(StompCommand.SUBSCRIBE, destination, authorization), null) != null;
    }

    @Test
    void aDestinationThatIsNotATenantJobFeedIsNotTheInterceptorsBusiness() {
        assertThat(allowed("/user/queue/notifications", null)).isTrue();
    }

    @Test
    void aJobFeedWithNoTokenOnTheFrameIsRefused() {
        assertThat(allowed("/topic/jobs." + TENANT_A, null)).isFalse();
    }

    @Test
    void aRefreshTokenIsRefused() {
        assertThat(allowed("/topic/jobs." + TENANT_A, refresh(UserRole.TENANT_ADMIN, TENANT_A))).isFalse();
    }

    @Test
    void aPlatformAdminMayWatchAnyTenantAndTheCrossTenantFeed() {
        String admin = access(UserRole.PLATFORM_ADMIN, null);
        assertThat(allowed("/topic/jobs." + TENANT_B, admin)).isTrue();
        assertThat(allowed("/topic/jobs.all", admin)).isTrue();
    }

    @Test
    void anyoneElseIsRefusedTheCrossTenantFeed() {
        assertThat(allowed("/topic/jobs.all", access(UserRole.TENANT_ADMIN, TENANT_A))).isFalse();
    }

    @Test
    void aTenantUserMayWatchTheirOwnTenant() {
        assertThat(allowed("/topic/jobs." + TENANT_A, access(UserRole.TENANT_USER, TENANT_A))).isTrue();
    }

    /**
     * Refused by dropping the frame -- the same answer as a destination that does not exist,
     * so a refusal never confirms that another tenant's id is real.
     */
    @Test
    void anotherTenantsFeedIsRefusedTheSameWayAsNoFeedAtAll() {
        String mine = access(UserRole.TENANT_ADMIN, TENANT_A);
        assertThat(allowed("/topic/jobs." + TENANT_B, mine)).isFalse();
        assertThat(allowed("/topic/jobs.999999", mine)).isFalse();
    }

    @Test
    void aTamperedTokenIsRefused() {
        String token = access(UserRole.TENANT_ADMIN, TENANT_A);
        assertThat(allowed("/topic/jobs." + TENANT_A, token.substring(0, token.length() - 3) + "abc")).isFalse();
    }

    // ---- CONNECT ----------------------------------------------------------------------------------


    private StompHeaderAccessor connected(String authorization) {
        Message<?> out = new StompAuthChannelInterceptor(this.jwtUtil)
            .preSend(frame(StompCommand.CONNECT, null, authorization), null);
        return StompHeaderAccessor.wrap(out);
    }

    @Test
    void aValidAccessTokenBecomesTheSessionsPrincipal() {
        assertThat(connected(access(UserRole.TENANT_USER, TENANT_A)).getUser().getName())
            .isEqualTo("ops@medaxis.example");
    }

    @Test
    void aRefreshTokenConnectsWithNoPrincipal() {
        assertThat(connected(refresh(UserRole.TENANT_USER, TENANT_A)).getUser()).isNull();
    }

    /** DEFECT, pinned as-is: an unauthenticated CONNECT is accepted, not refused. */
    @Test
    void aConnectWithNoTokenIsAcceptedAsAnAnonymousSession() {
        Message<?> out = new StompAuthChannelInterceptor(this.jwtUtil).preSend(frame(StompCommand.CONNECT, null, null), null);
        assertThat(out).isNotNull();
        assertThat(StompHeaderAccessor.wrap(out).getUser()).isNull();
    }
}
