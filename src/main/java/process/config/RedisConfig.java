package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
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
import java.util.HashMap;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 * */
@Configuration
@EnableCaching
public class RedisConfig extends CachingConfigurerSupport {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

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

    // Deliberately an overload of, and not an override of, CachingConfigurerSupport.cacheManager():
    // that inherited no-arg method keeps returning null, which is how a CachingConfigurer says
    // "resolve the CacheManager by type from the context" -- and by type it finds exactly this
    // bean. Extending the support class to supply an error handler therefore changes nothing
    // about which CacheManager is in use.
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues()
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

        Map<String, RedisCacheConfiguration> perCacheConfig = new HashMap<>();
        perCacheConfig.put("fileChatExtract", config.entryTtl(Duration.ofDays(7)));
        perCacheConfig.put("fileChatMetadata", config.entryTtl(Duration.ofSeconds(30)));
        // Every FTP/FTPS operation pays a fresh TCP connect, login and (for FTPS) TLS handshake
        // -- around 1.5s plain and 2.3s secured, against ~55ms for MinIO. A short TTL collapses
        // the repeat listings a single folder view triggers (the browse call and the folder
        // insights pass hit the same directory) without holding a stale view for long. Writes
        // evict the affected connection immediately, so this only ever delays noticing a change
        // made outside the app.
        perCacheConfig.put("ftpListing", config.entryTtl(Duration.ofSeconds(45)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .withInitialCacheConfigurations(perCacheConfig)
            .build();
    }

    /**
     * The handler Spring consults when a cache operation itself blows up, rather than the method
     * behind it. Registering one is the entire reason this class is a CachingConfigurer.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new DegradeToMissCacheErrorHandler();
    }

    /**
     * Turns an unreachable Redis into a cache miss instead of a failed request.
     *
     * With no CachingConfigurer supplying one, Spring installs SimpleCacheErrorHandler, and that
     * one rethrows whatever the cache threw. So with Redis down, the @Cacheable metadata lookups
     * on the way into the chat panel -- FileChatServiceImpl's prepareContext and sendMessage both
     * call getObjectMetadataCached, neither of them inside a try/catch -- let Lettuce's
     * RedisConnectionFailureException straight out of the caching proxy, and opening the panel
     * answered HTTP 500 while MinIO, Ollama and the model endpoint were all perfectly healthy.
     *
     * A cache is an optimisation. An unavailable one has to mean "miss", never "fail".
     *
     * Every method logs at warn rather than staying silent: a cache that is quietly failing every
     * operation looks, from the outside, exactly like one that is working but always cold, and
     * the difference is the whole of an incident.
     */
    private static final class DegradeToMissCacheErrorHandler implements CacheErrorHandler {

        /**
         * Safe to swallow: a read that cannot answer IS a miss. CacheInterceptor's get path
         * treats the null this leaves behind as "not cached", falls through and invokes the real
         * method, so the caller still gets the true value -- only paying the MinIO round trip or
         * the extraction it would have paid on a cold cache anyway.
         */
        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            logger.warn("Cache '{}' could not be read for key '{}' -- treating it as a miss and computing the value.",
                cache.getName(), key, exception);
        }

        /**
         * Safe to swallow: by the time a put runs the value has already been computed and is on
         * its way back to the caller, so losing the write costs the next caller a recomputation
         * and nothing else. Nothing here reads back a value it only ever wrote to the cache --
         * fileChatExtract and fileChatMetadata memoize work that MinIO and the extractor can
         * always redo; neither is a store of record.
         */
        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            logger.warn("Cache '{}' could not be written for key '{}' -- the value was returned to the caller, just not cached.",
                cache.getName(), key, exception);
        }

        /**
         * Swallowed because the write this eviction accompanies -- a delete, an overwrite, a
         * rename -- has already succeeded against storage, and must not be reported to the user
         * as failed because a cache is down.
         *
         * The honest cost, since StorageBrowserServiceImpl's write paths now depend on these
         * evictions: an entry that should have been dropped survives its TTL, so a file deleted
         * while Redis is unreachable can still have its extracted text in Redis once Redis comes
         * back. Rethrowing instead would leave that same entry AND leave the object undeleted,
         * which is strictly worse -- the user would retry into the same failure.
         */
        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            logger.warn("Cache '{}' could not evict key '{}' -- the storage write already succeeded; the entry will expire on its TTL.",
                cache.getName(), key, exception);
        }

        /**
         * Same reasoning as evict, and this is the one that actually fires for the write paths:
         * they evict with allEntries = true, which Spring turns into Cache.clear().
         */
        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            logger.warn("Cache '{}' could not be cleared -- the storage write already succeeded; entries will expire on their TTL.",
                cache.getName(), exception);
        }
    }

}
