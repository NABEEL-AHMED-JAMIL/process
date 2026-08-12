package process.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import process.security.JwtAuthenticationFilter;
import process.util.JwtUtil;

/**
 * Phase 0 JWT auth. Public (no token required): login/refresh, the WebSocket handshake, the
 * external Kafka-consumer worker's status-callback endpoints (NotifyResetApi -- those are
 * called by a separate backend process, not the browser, so they can't carry a user's JWT),
 * health/swagger. Everything else requires a valid "Authorization: Bearer <accessToken>"
 * header, validated by JwtAuthenticationFilter (registered ahead of the standard
 * UsernamePasswordAuthenticationFilter since login here isn't form-based).
 * @author Nabeel Ahmed
 */
@Configuration
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final JwtUtil jwtUtil;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
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
                .antMatchers("/auth.json/**").permitAll()
                .antMatchers("/ws/**").permitAll()
                .antMatchers("/changeState/**", "/addLogs/**").permitAll()
                .antMatchers("/actuator/**").permitAll()
                .antMatchers("/swagger-ui/**", "/swagger-ui.html", "/v2/api-docs",
                    "/swagger-resources/**", "/webjars/**").permitAll()
                .anyRequest().authenticated().and()
            .addFilterBefore(new JwtAuthenticationFilter(this.jwtUtil), UsernamePasswordAuthenticationFilter.class);
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) {
        // No AuthenticationProvider registered -- login is handled entirely by AuthServiceImpl
        // (manual PasswordEncoder.matches against AppUserRepository), not Spring Security's
        // AuthenticationManager/UserDetailsService flow. This filter chain only validates
        // already-issued JWTs (see JwtAuthenticationFilter).
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

}
