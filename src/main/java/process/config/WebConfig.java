package process.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Previously registered TenantFilterInterceptor here -- removed, it never actually worked (see
 * process.security.TenantFilterHelper's javadoc for why: enabling a Hibernate filter from a
 * pre-controller HandlerInterceptor can't scope a later @Transactional service method's own
 * query). Tenant filtering is now done per-method via TenantFilterHelper.enableIfNeeded(),
 * called at the top of each @Transactional service method that queries a filtered entity.
 * @author Nabeel Ahmed
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
