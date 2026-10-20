package process.config;

import process.security.JwtAuthenticationFilter;
import process.identity.IdentityPort;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The HTTP rules (MIG-389: Spring Security 7's SecurityFilterChain bean and lambda DSL, in place of Boot 2.7's
 * WebSecurityConfigurerAdapter; the same rules in the same order). Spring Security 6+ authorises every dispatch, so the
 * ERROR and FORWARD dispatches are let through, as 5.7's once-per-request check let Boot's /error answer a 401 or 404.
 *
 * @author Nabeel Ahmed
 * */
@Configuration
public class SecurityConfig {

    private final IdentityPort identity;

    public SecurityConfig(IdentityPort identity) {
        this.identity = identity;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, ex) ->
                response.sendError(401, "Unauthorized")))
            .authorizeHttpRequests(requests -> requests
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Service to service (MIG-22): InternalSecretRestApi checks INTERNAL_SERVICE_TOKEN itself,
                // and the gateway refuses /api/v1/internal from outside.
                .requestMatchers(HttpMethod.POST, "/internal/**").permitAll()
                // MIG-92: Identity's public signing keys. Not a secret; the gateway keeps /internal inside.
                .requestMatchers(HttpMethod.GET, "/internal/jwks").permitAll()
                // "/addLogs/**" does not cover "/addLogsBatch/..." -- a pattern does not
                // match across the segment boundary -- so every batched log line was rejected
                // with a 401 and silently lost. The batch endpoint is listed in its own right.
                .requestMatchers("/changeState/**", "/addLogs/**", "/addLogsBatch/**").permitAll()
                // The meter asks whether a run's token is live; the token is the proof.
                .requestMatchers(HttpMethod.POST, "/meter.json/verifyRun").permitAll()
                // MIG-167: a worker fetches its run's configuration with the run's token; RunConfigResolver checks it.
                .requestMatchers(HttpMethod.POST, "/runConfig.json/resolve").permitAll()
                // MIG-278: a form shared by link -- the token in the path is the proof; PublicForms checks it, the link's
                // expiry and uses, the workspace's switch, a signed ticket and rate limits. Reads that form only.
                .requestMatchers("/publicForm.json/*", "/publicForm.json/*/upload", "/publicForm.json/*/submit").permitAll()

                // MIG-332: the customer API (the gateway's /v1). JwtAuthenticationFilter admits only an API client's token here
                // and answers anything else with a problem; no person reaches it, and an API client reaches nothing else.
                // MIG-334: a file's signed link -- the token in the query is the proof (FileLinks); it opens that one file only.
                .requestMatchers(HttpMethod.GET, "/customer/files/*/content").permitAll()
                // MIG-335: a run's view link -- the token in the path is the proof (ViewLinks); it reads that one run only.
                .requestMatchers(HttpMethod.GET, "/customer/embed/runs/*", "/customer/embed/runs/*/frame").permitAll()
                .requestMatchers("/customer/**").hasRole("API_CLIENT")
                // Liveness probes have no account, so health and info stay open. The rest --
                // metrics and prometheus -- name every endpoint, its call rate and its 401/403
                // rate, which is a map of the application for anyone who asks for it.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("PLATFORM_ADMIN")
                // The API documentation is a map of every endpoint and model: a platform admin's only
                // (owner, 2026-09-24), and generated at all only where process.api-docs.enabled says so.
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs/**").hasRole("PLATFORM_ADMIN")
                // Everything else -- form.json/rebuildDataset (3d2978c3) among them -- needs a signed-in caller, and its
                // @PreAuthorize decides the rest.
                .anyRequest().authenticated())
            .addFilterBefore(new JwtAuthenticationFilter(this.identity), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
