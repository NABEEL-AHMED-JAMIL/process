package process.config;

import org.barco.platform.cache.RedisVersionStore;
import org.barco.platform.cache.VersionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The shared versions that keep process's in-memory caches in step across instances (MIG-110):
 * the lookup cache and the page-access cache, under "{process}:cache-version:<cache>" in Redis.
 */
@Configuration
public class SharedCacheConfig {

    @Bean
    public VersionStore cacheVersions(StringRedisTemplate redis) {
        return new RedisVersionStore(redis, "{process}");
    }
}
