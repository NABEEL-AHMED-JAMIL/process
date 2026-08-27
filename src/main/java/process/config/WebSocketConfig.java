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
 * */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public Logger logger = LogManager.getLogger(WebSocketConfig.class);

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("#{'${websocket.allowed-origins:http://localhost,http://localhost:8080}'.split(',')}")
    private String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(this.allowedOrigins)
            .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // /topic carries the per-tenant job feed; StompAuthChannelInterceptor checks that a
        // subscriber's token matches the tenant named in the destination.
        config.enableSimpleBroker("/queue", "/user", "/topic");
        config.setApplicationDestinationPrefixes("/api/v1");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(this.stompAuthChannelInterceptor);
    }
}
