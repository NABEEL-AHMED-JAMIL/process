package process.config;

import org.barco.platform.security.HttpManagedActionAudit;
import org.barco.platform.security.ManagementModeInterceptor;
import org.barco.platform.security.WorkspaceDataGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MIG-244: the workspace's management mode, after JwtAuthenticationFilter has set the caller. A handler marked
 * {@code @BuilderAction} -- a pipeline, a schedule, a source, a model choice, a run -- is refused (403) to the customer
 * in a MANAGED workspace; every write of a managed-service session (our staff) is recorded in Identity's
 * managed_action_log before it runs, and refused (503) when it cannot be.
 */
@Configuration
public class ManagementModeConfig implements WebMvcConfigurer {

    private final ManagementModeInterceptor interceptor;

    public ManagementModeConfig(@Value("${identity.url:http://identity:9160}") String identityUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this.interceptor = new ManagementModeInterceptor("process", new HttpManagedActionAudit(identityUrl, serviceToken));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): a handler marked @WorkspaceData -- a workspace's
        // runs, schedules, pipelines and outputs -- answers a platform administrator's own sign-in 403 before it runs;
        // a managed-service session passes, its writes and its @AuditedRead data reads audited below.
        registry.addInterceptor(new WorkspaceDataGuard()).addPathPatterns("/**");
        registry.addInterceptor(this.interceptor).addPathPatterns("/**");
    }
}
