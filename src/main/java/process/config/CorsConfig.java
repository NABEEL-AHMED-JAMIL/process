package process.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.util.UrlPathHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Core's whole CORS policy, in one place (risk register #6, 2026-10-12). It replaces the {@code @CrossOrigin(origins = "*")}
 * every console controller carried, and CorsPolicyGuardTest fails the build if a controller adds one again.
 *
 * The gateway does not answer CORS: PreflightPassThrough hands every preflight to the service behind the route, so this is
 * the answer the console's browser gets for Core's routes. Spring Security's {@code .cors()} (SecurityConfig) finds this bean
 * by its name and runs it ahead of JwtAuthenticationFilter, so a 401 or 403 carries Access-Control-Allow-Origin too and the
 * console can read it (an expired token is refreshed, a refusal shows its message) -- see the new-service checklist.
 *
 * <ul>
 *   <li>The console's paths: only the origins in {@code process.cors.allowed-origins} (CORS_ALLOWED_ORIGINS, comma-separated:
 *       the console's own address, http://localhost:4400 on this development stack). No cookies are involved (bearer
 *       tokens), so credentials stay off. Content-Disposition and Content-Length are exposed for downloads.</li>
 *   <li>No CORS at all -- as before, when those controllers carried no annotation -- for service-to-service and worker
 *       paths (/internal, /runConfig.json) and the customer API (/customer: the gateway's /v1), except the embeddable run
 *       view (/customer/embed), which the console's own page reads.</li>
 * </ul>
 */
@Configuration
public class CorsConfig {

    static final List<String> NO_CORS = Collections.unmodifiableList(Arrays.asList("/internal/**", "/runConfig.json/**",
        "/customer/**"));
    static final String CONSOLE_READS_IT = "/customer/embed/**";

    private static final AntPathMatcher PATTERNS = new AntPathMatcher();

    /** Named so: Spring Security's .cors() uses the bean called corsConfigurationSource. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${process.cors.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration console = console(allowedOrigins);
        UrlPathHelper paths = new UrlPathHelper();
        return request -> {
            String path = paths.getPathWithinApplication(request);
            return covered(path) ? console : null;
        };
    }

    /** Whether a path (within the application, without /api/v1) answers CORS at all. */
    static boolean covered(String path) {
        if (PATTERNS.match(CONSOLE_READS_IT, path)) {
            return true;
        }
        for (String pattern : NO_CORS) {
            if (PATTERNS.match(pattern, path)) {
                return false;
            }
        }
        return true;
    }

    /** The console's policy: the configured origins only; none configured lets no browser origin in. */
    static CorsConfiguration console(String allowedOrigins) {
        List<String> origins = new ArrayList<>();
        for (String origin : (allowedOrigins == null ? "" : allowedOrigins).split(",")) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
            }
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(Arrays.asList("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(Collections.singletonList(CorsConfiguration.ALL));
        cors.setExposedHeaders(Arrays.asList(HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH));
        cors.setAllowCredentials(false);
        cors.setMaxAge(1800L);
        return cors;
    }
}
