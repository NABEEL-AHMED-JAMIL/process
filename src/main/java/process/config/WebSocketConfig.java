package process.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import process.security.StompAuthChannelInterceptor;

/**
 * @author Nabeel Ahmed
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public Logger logger = LogManager.getLogger(WebSocketConfig.class);

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    // Every other externally-facing endpoint in this app (Minio/S3/Azure, Kafka, mail, JWT/
    // encryption keys) is environment-variable driven -- this one was a literal, which meant the
    // SockJS/STOMP handshake's origin check silently rejected connections (live job-status push
    // stops working, with nothing else broken) the moment the frontend was served from anything
    // other than localhost. Default preserves today's dev behavior exactly; set
    // WEBSOCKET_ALLOWED_ORIGINS in staging/production to the real frontend origin(s).
    @Value("#{'${websocket.allowed-origins:http://localhost,http://localhost:8080}'.split(',')}")
    private String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    /**
     * Method use to register stomp endpoint with origins allow
     * @param registry
     * */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(this.allowedOrigins)
            .withSockJS();
    }

    /**
     * Method use to config the broker
     * @param config
     * */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/queue", "/user");
        config.setApplicationDestinationPrefixes("/api/v1");
    }

    /**
     * Method use to authenticate the STOMP CONNECT frame (JWT) and set the resulting username
     * as the session Principal -- see StompAuthChannelInterceptor.
     * @param registration
     * */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(this.stompAuthChannelInterceptor);
    }
}
