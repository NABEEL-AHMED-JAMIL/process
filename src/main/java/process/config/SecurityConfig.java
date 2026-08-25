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
                // "/addLogs/**" does not cover "/addLogsBatch/..." -- an Ant pattern does not
                // match across the segment boundary -- so every batched log line was rejected
                // with a 401 and silently lost. The batch endpoint is listed in its own right.
                .antMatchers("/changeState/**", "/addLogs/**", "/addLogsBatch/**").permitAll()

                .antMatchers("/dynamicForm.json/fetchFormByUuid", "/dynamicForm.json/fetchSubmissionByUuid").permitAll()
                .antMatchers("/actuator/**").permitAll()
                .antMatchers("/swagger-ui/**", "/swagger-ui.html", "/v2/api-docs",
                    "/swagger-resources/**", "/webjars/**").permitAll()
                .anyRequest().authenticated().and()
            .addFilterBefore(new JwtAuthenticationFilter(this.jwtUtil), UsernamePasswordAuthenticationFilter.class);
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) {

    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

}
