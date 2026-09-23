package process.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.util.JwtUtil;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUBSCRIBE is an allowlist: the four destinations the consoles use, and nothing else.
 *
 * The broker matches a subscription as an Ant pattern, so a subscription to /topic/** receives
 * every message sent to any /topic destination. The interceptor used to check only destinations
 * beginning /topic/jobs. and wave everything else through -- so any signed-in user of any tenant
 * could subscribe to /topic/** and watch every tenant's jobs, or to /queue/** and read every other
 * user's notifications, which Spring delivers to /queue/notifications-user{sessionId}.
 */
class StompSubscribeAllowlistTest {

    private static final long MY_TENANT = 2901L;
    private static final long OTHER_TENANT = 2905L;

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
        AppUser user = new AppUser();
        user.setAppUserId(10L);
        user.setUsername("ops@medaxis.example");
        user.setTenantId(tenantId);
        user.setUserRole(role);
        return "Bearer " + this.jwtUtil.generateAccessToken(user);
    }

    private boolean allowed(String destination, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        if (destination != null) accessor.setDestination(destination);
        if (authorization != null) accessor.addNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new StompAuthChannelInterceptor(this.jwtUtil).preSend(frame, null) != null;
    }

    /** Why this matters: the simple broker's own registry, fed a wildcard, matches another tenant's feed. */
    @Test
    void theBrokerTreatsASubscriptionAsAPattern() {
        DefaultSubscriptionRegistry registry = new DefaultSubscriptionRegistry();
        registry.registerSubscription(subscribe("s1", "sub-0", "/topic/**"));
        registry.registerSubscription(subscribe("s2", "sub-0", "/queue/**"));

        assertThat(registry.findSubscriptions(send("/topic/jobs." + OTHER_TENANT))).containsKey("s1");
        assertThat(registry.findSubscriptions(send("/queue/notifications-userabc123"))).containsKey("s2");
    }

    @Test
    void aWildcardIsRefusedForATenantUser() {
        String mine = access(UserRole.TENANT_USER, MY_TENANT);
        assertThat(allowed("/topic/**", mine)).isFalse();
        assertThat(allowed("/topic/*", mine)).isFalse();
        assertThat(allowed("/topic/jobs*", mine)).isFalse();
        assertThat(allowed("/topic/jobs.*", mine)).isFalse();
        assertThat(allowed("/**", mine)).isFalse();
    }

    @Test
    void anotherUsersQueueIsRefused() {
        String mine = access(UserRole.TENANT_ADMIN, MY_TENANT);
        assertThat(allowed("/queue/**", mine)).isFalse();
        assertThat(allowed("/queue/notifications-userabc123", mine)).isFalse();
        assertThat(allowed("/user/**", mine)).isFalse();
    }

    /** A platform admin has /topic/jobs.all; a wildcard would also reach every user's private queue. */
    @Test
    void aPlatformAdminGetsNoWildcardEither() {
        String admin = access(UserRole.PLATFORM_ADMIN, null);
        assertThat(allowed("/topic/**", admin)).isFalse();
        assertThat(allowed("/queue/**", admin)).isFalse();
    }

    @Test
    void aDestinationNoConsoleUsesIsRefused() {
        String mine = access(UserRole.TENANT_USER, MY_TENANT);
        assertThat(allowed("/topic/something-else", mine)).isFalse();
        assertThat(allowed("/topic/jobs.", mine)).isFalse();
        assertThat(allowed(null, mine)).isFalse();
    }

    /** What the consoles actually subscribe to still works. */
    @Test
    void theConsolesOwnSubscriptionsAreAllowed() {
        assertThat(allowed("/topic/jobs." + MY_TENANT, access(UserRole.TENANT_USER, MY_TENANT))).isTrue();
        assertThat(allowed("/topic/jobs.all", access(UserRole.PLATFORM_ADMIN, null))).isTrue();
        // The old console's per-user queues: resolved against the CONNECT principal, so no frame token.
        assertThat(allowed("/user/queue/reply", null)).isTrue();
        assertThat(allowed("/user/queue/notifications", null)).isTrue();
    }

    private static Message<byte[]> subscribe(String session, String id, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setSessionId(session);
        accessor.setSubscriptionId(id);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> send(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
