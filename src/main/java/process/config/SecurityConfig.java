package process.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import process.security.JwtAuthenticationFilter;
import process.identity.IdentityPort;

/**
 * @author Nabeel Ahmed
 * */
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final IdentityPort identity;

    public SecurityConfig(IdentityPort identity) {
        this.identity = identity;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
            .cors().and()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
            .exceptionHandling().authenticationEntryPoint((request, response, ex) ->
                response.sendError(401, "Unauthorized")).and()
            .authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .antMatchers("/ws/**").permitAll()
                // Service to service (MIG-22): InternalSecretRestApi checks INTERNAL_SERVICE_TOKEN itself,
                // and the gateway refuses /api/v1/internal from outside.
                .antMatchers(HttpMethod.POST, "/internal/**").permitAll()
                // MIG-92: Identity's public signing keys. Not a secret; the gateway keeps /internal inside.
                .antMatchers(HttpMethod.GET, "/internal/jwks").permitAll()
                // "/addLogs/**" does not cover "/addLogsBatch/..." -- an Ant pattern does not
                // match across the segment boundary -- so every batched log line was rejected
                // with a 401 and silently lost. The batch endpoint is listed in its own right.
                .antMatchers("/changeState/**", "/addLogs/**", "/addLogsBatch/**").permitAll()
                // The meter asks whether a run's token is live; the token is the proof.
                .antMatchers(HttpMethod.POST, "/meter.json/verifyRun").permitAll()
                // MIG-167: a worker fetches its run's configuration with the run's token; RunConfigResolver checks it.
                .antMatchers(HttpMethod.POST, "/runConfig.json/resolve").permitAll()

                // Liveness probes have no account, so health and info stay open. The rest --
                // metrics and prometheus -- name every endpoint, its call rate and its 401/403
                // rate, which is a map of the application for anyone who asks for it.
                .antMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .antMatchers("/actuator/**").hasRole("PLATFORM_ADMIN")
                // The API documentation is a map of every endpoint and model: a platform admin's only
                // (owner, 2026-09-24), and generated at all only where process.api-docs.enabled says so.
                .antMatchers("/swagger-ui/**", "/swagger-ui.html", "/v2/api-docs",
                    "/swagger-resources/**", "/webjars/**").hasRole("PLATFORM_ADMIN")
                .anyRequest().authenticated().and()
            .addFilterBefore(new JwtAuthenticationFilter(this.identity), UsernamePasswordAuthenticationFilter.class);
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) {

    }

}
