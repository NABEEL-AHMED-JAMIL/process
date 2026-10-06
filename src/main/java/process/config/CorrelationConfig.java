package process.config;

import org.barco.platform.correlation.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The request's X-Correlation-Id (the gateway sets or forwards one), bound for the request and echoed on
 * the answer, as storage-service and media-service do. An ad-hoc model call's usage key is derived from it
 * (MIG-198) so a replayed request is billed once. Ahead of authentication, so a refusal carries it too.
 */
@Configuration
public class CorrelationConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
