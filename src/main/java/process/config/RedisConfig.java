package process.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import java.time.Duration;

/**
 * Redis is used for two things in this app:
 *  1. The per-user WebSocket presence registry (see WebSocketPresenceService) -- who's online
 *     right now, so a status push only fires at a session that's actually there to receive it.
 *     Backing that in Redis (instead of an in-process map) means presence survives an app
 *     restart and is correct if this ever runs as more than one instance.
 *  2. General @Cacheable read-through caching for read-heavy, rarely-changing data (currently
 *     just SettingServiceImpl.appSetting). LookupData is global, but SourceTaskType is
 *     tenant-scoped (each tenant's own rows plus shared/global ones) -- appSetting()'s
 *     @Cacheable key is per-caller (tenantId, or a fixed key for PLATFORM_ADMIN) precisely so
 *     one tenant's cached response is never served back to another. Tenant/AppUser lists were
 *     deliberately NOT cached here:
 *     they carry live counts (Tenant.userCount) or are cheap+security-sensitive enough that the
 *     staleness/complexity trade-off isn't worth it.
 * @author Nabeel Ahmed
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /** Plain String key/value template for the presence registry -- WebSocketPresenceService
     * does its own key naming and TTL management, so no need for anything fancier here.
     * @param connectionFactory
     * @return RedisTemplate<String, String>
     * */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /** Method use to build the CacheManager behind @Cacheable/@CacheEvict -- a conservative
     * 10-minute default TTL so a stale cache entry can't linger indefinitely if a @CacheEvict
     * is ever missed on some write path, without every cache needing its own explicit config.
     * Values are JSON (GenericJackson2JsonRedisSerializer), not Java's default JDK
     * serialization -- the ResponseDto/*Dto classes this caches don't implement Serializable
     * (nothing in this codebase does), so the default would throw on every cache write.
     * @param connectionFactory
     * @return CacheManager
     * */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues()
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }

}
