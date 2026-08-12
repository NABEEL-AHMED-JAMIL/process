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
import java.security.Principal;
import java.util.Collections;

/**
 * Authenticates the STOMP CONNECT frame with the same JWT used for regular REST calls, and sets
 * the resulting username as the session's Principal -- that Principal is what
 * SimpMessagingTemplate.convertAndSendToUser(username, ...) matches against, which is what
 * makes per-user WebSocket push actually per-user (see NotificationService). Without this, a
 * STOMP session has no Principal at all and convertAndSendToUser can't target it.
 *
 * The HTTP handshake itself (/ws/**) stays permitAll in SecurityConfig -- auth happens here, on
 * the CONNECT frame, not the handshake. An invalid/missing/expired token is not a hard failure:
 * the connection still succeeds (SockJS/STOMP-level), it just has no Principal, so per-user
 * sends silently never reach it (mirrors JwtAuthenticationFilter's "leave unauthenticated,
 * don't reject" philosophy for the HTTP side).
 * @author Nabeel Ahmed
 */
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

}
