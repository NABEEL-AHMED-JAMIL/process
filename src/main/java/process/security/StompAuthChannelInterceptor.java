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
