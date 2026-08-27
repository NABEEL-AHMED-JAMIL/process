package process.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import process.util.JwtUtil;
import process.socket.JobEventPublisher;
import java.security.Principal;
import java.util.Collections;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final Logger logger = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private final JwtUtil jwtUtil;

    public StompAuthChannelInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return this.checkSubscribe(accessor, message);
        }
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    Claims claims = this.jwtUtil.parseClaims(authHeader.substring(7));
                    if (!this.jwtUtil.isRefreshToken(claims)) {
                        String username = claims.getSubject();
                        String userRole = this.jwtUtil.userRoleOf(claims);
                        Principal principal = new UsernamePasswordAuthenticationToken(
                            username, null, Collections.singletonList(() -> "ROLE_" + userRole));
                        accessor.setUser(principal);
                        this.logger.debug("WebSocket CONNECT authenticated as {}", username);
                    }
                } catch (JwtException | IllegalArgumentException ex) {
                    this.logger.debug("Rejected WebSocket CONNECT token: {}", ex.getMessage());
                }
            } else {
                this.logger.debug("WebSocket CONNECT with no Authorization header -- connecting unauthenticated");
            }
        }
        return message;
    }

    /**
     * The tenant job feed names its tenant in the destination, so without this any connected
     * user could subscribe to /topic/jobs.{someoneElse} and watch another tenant's jobs
     * change. Returning null drops the frame, which STOMP treats as a refused subscription.
     */
    private Message<?> checkSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(JobEventPublisher.TENANT_JOB_TOPIC)) {
            return message;
        }
        String requested = destination.substring(JobEventPublisher.TENANT_JOB_TOPIC.length());
        String token = accessor.getFirstNativeHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            this.logger.debug("Refused SUBSCRIBE to {} -- no token on the frame", destination);
            return null;
        }
        try {
            Claims claims = this.jwtUtil.parseClaims(token.substring(7));
            if (this.jwtUtil.isRefreshToken(claims)) {
                return null;
            }
            // A platform admin works across tenants, so it may watch any of them -- and it
            // is the only role allowed on the cross-tenant feed.
            boolean isPlatformAdmin = "PLATFORM_ADMIN".equals(this.jwtUtil.userRoleOf(claims));
            if (isPlatformAdmin) {
                return message;
            }
            if (JobEventPublisher.ALL_TENANTS.equals(requested)) {
                this.logger.warn("Refused SUBSCRIBE to the cross-tenant job feed for a non-admin token");
                return null;
            }
            Long tenantId = this.jwtUtil.tenantIdOf(claims);
            if (tenantId != null && tenantId.toString().equals(requested)) {
                return message;
            }
            this.logger.warn("Refused SUBSCRIBE to {} for a token scoped to tenant {}", destination, tenantId);
            return null;
        } catch (JwtException | IllegalArgumentException ex) {
            this.logger.debug("Refused SUBSCRIBE to {}: {}", destination, ex.getMessage());
            return null;
        }
    }

}
