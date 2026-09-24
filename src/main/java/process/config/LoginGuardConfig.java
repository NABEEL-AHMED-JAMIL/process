package process.config;

import process.identity.IdentityInProcess;
import org.barco.platform.security.LoginAttemptGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * The sign-in attempt guard, platform-commons' since 1.7.0 (MIG-109 AC 5): one implementation for
 * Identity and any later sign-in surface. Counts live in the shared Redis under this prefix.
 *
 * @author Nabeel Ahmed
 */
@IdentityInProcess
@Configuration
public class LoginGuardConfig {

    @Bean
    public LoginAttemptGuard loginAttemptGuard(@Qualifier("redisTemplate") RedisTemplate<String, String> redis,
        @Value("${identity.login-guard.key-prefix:" + LoginAttemptGuard.DEFAULT_PREFIX + "}") String prefix) {
        return new LoginAttemptGuard(redis, prefix, () -> System.currentTimeMillis() / 1000);
    }
}
