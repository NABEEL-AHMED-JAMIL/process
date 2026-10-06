package process.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import process.security.PageAccessInterceptor;

/**
 * @author Nabeel Ahmed
 * */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PageAccessInterceptor pageAccessInterceptor;

    public WebConfig(PageAccessInterceptor pageAccessInterceptor) {
        this.pageAccessInterceptor = pageAccessInterceptor;
    }

    /**
     * Page access is enforced on the way in, after JwtAuthenticationFilter has set the tenant
     * context and before any controller runs. Registered against everything: the interceptor
     * itself decides which paths are gated (PageKey.pagesGating) so the list lives in one place.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this.pageAccessInterceptor).addPathPatterns("/**");
    }
}
