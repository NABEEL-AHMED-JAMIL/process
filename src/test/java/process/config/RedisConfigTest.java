package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.cache.support.NoOpCache;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.multipart.MultipartFile;
import process.model.service.impl.StorageBrowserServiceImpl;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things about the cache that were not true and cost a production incident each.
 *
 * (1) RedisConfig registered a RedisCacheManager and no CacheErrorHandler, so Spring installed
 *     SimpleCacheErrorHandler, which rethrows. With Redis down, the @Cacheable metadata lookup on
 *     the way into the chat panel (FileChatServiceImpl calls getObjectMetadataCached from both
 *     prepareContext and sendMessage, neither inside a try/catch) let Lettuce's
 *     RedisConnectionFailureException out of the caching proxy and the panel returned HTTP 500 --
 *     with MinIO, Ollama and the model endpoint all healthy.
 *
 * (2) None of StorageBrowserServiceImpl's write paths evicted, so a deleted file's full extracted
 *     plaintext stayed in Redis for the rest of a seven-day TTL and a replaced file could still be
 *     answered from its previous version.
 *
 * The write-path assertions live in this class rather than beside StorageBrowserServiceImpl
 * because they are really assertions about the cache contract this file configures: the TTLs that
 * make a missed eviction expensive are seven days and thirty seconds, and they are set right here.
 *
 * @author Nabeel Ahmed
 */
public class RedisConfigTest {

    // ---------------------------------------------------------------------------------------
    // (1) A Redis outage must read as a cache miss, not as a failed request.
    // ---------------------------------------------------------------------------------------

    /**
     * The real proxy, not a hand-call of the handler: this is the only way to show that the
     * caller gets the true value back rather than an exception, because it is CacheInterceptor --
     * not the handler -- that decides what a swallowed get means.
     */
    @Test
    void aRedisOutageReadsAsACacheMissAndTheCallerStillGetsTheValue() {
        CachedLikeFileChatImpl target = new CachedLikeFileChatImpl();
        CachedLikeFileChat proxy = this.proxiedWith(new RedisConfig().errorHandler(), target);

        String etag = assertDoesNotThrow(() -> proxy.metadataFor("etl-bucket", "cv.pdf"),
            "an unreachable Redis must degrade to a miss, not surface as an error to the caller");

        assertEquals("etag-of-etl-bucket/cv.pdf", etag,
            "a swallowed cache get has to fall through to the real method, not return null");
        assertEquals(1, target.realInvocations.get(),
            "the real method should have been invoked exactly once -- that is what a miss means");
    }

    /**
     * Pins the behaviour that caused the incident, so this stays a test of the fix and not of
     * Spring. Revert RedisConfig to a plain @Configuration and the test above starts throwing
     * exactly what this one asserts.
     */
    @Test
    void springsDefaultHandlerIsTheOneThatTurnedAnOutageIntoA500() {
        CachedLikeFileChatImpl target = new CachedLikeFileChatImpl();
        CachedLikeFileChat proxy = this.proxiedWith(new SimpleCacheErrorHandler(), target);

        assertThrows(RedisConnectionFailureException.class, () -> proxy.metadataFor("etl-bucket", "cv.pdf"),
            "SimpleCacheErrorHandler rethrows -- this is the 500 the chat panel was answering");
    }

    /**
     * The write paths added in this change evict with allEntries, which Spring turns into
     * Cache.clear(). A cache that cannot be cleared must not fail the delete or upload that has
     * already succeeded against storage.
     */
    @Test
    void aFailedEvictDoesNotFailTheStorageWriteItAccompanies() {
        CachedLikeFileChatImpl target = new CachedLikeFileChatImpl();
        CachedLikeFileChat proxy = this.proxiedWith(new RedisConfig().errorHandler(), target);

        assertDoesNotThrow(() -> proxy.deleteAndEvict("etl-bucket"),
            "the object is already gone from storage; an unreachable cache must not report failure");
        assertEquals(1, target.deletes.get(), "the write itself must still have run");

        CachedLikeFileChatImpl defaultTarget = new CachedLikeFileChatImpl();
        CachedLikeFileChat defaultProxy = this.proxiedWith(new SimpleCacheErrorHandler(), defaultTarget);
        assertThrows(RedisConnectionFailureException.class, () -> defaultProxy.deleteAndEvict("etl-bucket"),
            "without a handler the delete would report failure after having already deleted");
    }

    /** Every one of the four callbacks swallows, so no cache operation can ever be the failure. */
    @Test
    void everyCacheOperationIsSwallowedAndNoneRethrows() {
        CacheErrorHandler handler = new RedisConfig().errorHandler();
        Cache cache = new NoOpCache("fileChatExtract");
        RedisConnectionFailureException down = new RedisConnectionFailureException("Unable to connect to Redis");

        assertDoesNotThrow(() -> handler.handleCacheGetError(down, cache, "etl-bucket:cv.pdf:etag"));
        assertDoesNotThrow(() -> handler.handleCachePutError(down, cache, "etl-bucket:cv.pdf:etag", "some text"));
        assertDoesNotThrow(() -> handler.handleCacheEvictError(down, cache, "etl-bucket:cv.pdf:etag"));
        assertDoesNotThrow(() -> handler.handleCacheClearError(down, cache));
    }

    /**
     * Spring only consults an error handler it can find, and it finds it by autowiring
     * CachingConfigurer beans. A handler bean on its own would be ignored.
     */
    @Test
    void theHandlerIsRegisteredWhereSpringLooksForIt() {
        RedisConfig config = new RedisConfig();
        assertTrue(config instanceof CachingConfigurer,
            "RedisConfig must be a CachingConfigurer or its errorHandler() is never asked for");
        CacheErrorHandler handler = config.errorHandler();
        assertNotNull(handler, "a null handler silently falls back to the rethrowing default");
        assertFalse(handler instanceof SimpleCacheErrorHandler,
            "SimpleCacheErrorHandler is the rethrowing default this change exists to replace");
    }

    /**
     * Becoming a CachingConfigurer brought an inherited no-arg cacheManager() along with it, and
     * it has to keep returning null: null is how a configurer says "resolve the CacheManager by
     * type", which is how the @Bean below it -- the Redis one with the per-cache TTLs -- stays the
     * manager in use. Overriding it to return something else would quietly change every TTL.
     */
    @Test
    void theRedisCacheManagerBeanIsStillTheOneSpringResolves() {
        assertNull(new RedisConfig().cacheManager(),
            "the no-arg cacheManager() must stay null so the @Bean(RedisConnectionFactory) one wins");
    }

    // ---------------------------------------------------------------------------------------
    // (2) Every write path evicts both file-chat caches.
    // ---------------------------------------------------------------------------------------

    @Test
    void everyUploadEvictsBothFileChatCaches() throws Exception {
        this.assertEvictsBothFileChatCaches("uploadObject", String.class, String.class, MultipartFile.class);
        this.assertEvictsBothFileChatCaches("uploadObject", String.class, String.class, InputStream.class, long.class, String.class);
        this.assertEvictsBothFileChatCaches("uploadForWorkflow", String.class, String.class, MultipartFile.class);
        this.assertEvictsBothFileChatCaches("uploadForWorkflow", String.class, String.class, InputStream.class, long.class, String.class);
    }

    @Test
    void everyDeleteEvictsBothFileChatCaches() throws Exception {
        // deleteObject is the one that leaked: up to 500,000 characters of the deleted file's
        // text, left readable in Redis for the remaining seven days of its TTL.
        this.assertEvictsBothFileChatCaches("deleteObject", String.class, String.class);
        this.assertEvictsBothFileChatCaches("deleteObjects", String.class, List.class);
        this.assertEvictsBothFileChatCaches("deleteFolder", String.class, String.class);
    }

    @Test
    void renamingAFolderEvictsBothFileChatCaches() throws Exception {
        this.assertEvictsBothFileChatCaches("renameFolder", String.class, String.class, String.class);
    }

    /**
     * The eviction has to be allEntries, not a key: a delete never learns the etag that is part
     * of the fileChatExtract key, a folder operation never enumerates the keys underneath it, and
     * fileChatMetadata is keyed by the asking tenant and user rather than by the writer.
     */
    @Test
    void theEvictionsClearTheWholeCacheBecauseNoPreciseKeyExists() throws Exception {
        CacheEvict evict = this.evictOn("deleteObject", String.class, String.class);
        assertTrue(evict.allEntries(),
            "a per-key evict cannot work here -- the etag of the deleted object is unknown");
        assertFalse(evict.beforeInvocation(),
            "a write that threw changed nothing and must not clear a week of extractions on its way out");
    }

    private void assertEvictsBothFileChatCaches(String methodName, Class<?>... parameterTypes) throws Exception {
        CacheEvict evict = this.evictOn(methodName, parameterTypes);
        List<String> caches = Arrays.asList(evict.cacheNames());
        assertTrue(caches.contains("fileChatExtract"),
            methodName + " must evict fileChatExtract or the old file's full text outlives it: " + caches);
        assertTrue(caches.contains("fileChatMetadata"),
            methodName + " must evict fileChatMetadata or the stale etag answers for 30s more: " + caches);
        assertTrue(evict.allEntries(), methodName + " has no precise key to evict; it must clear");
    }

    /** findMergedAnnotation so that writing cacheNames = instead of value = still resolves. */
    private CacheEvict evictOn(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = StorageBrowserServiceImpl.class.getMethod(methodName, parameterTypes);
        CacheEvict evict = AnnotatedElementUtils.findMergedAnnotation(method, CacheEvict.class);
        assertNotNull(evict, methodName + " changes what is in storage and must carry @CacheEvict");
        return evict;
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the same caching proxy Spring would, over a cache manager that fails the way an
     * unreachable Redis does. afterSingletonsInstantiated() matters: without it the interceptor
     * stays uninitialised and skips caching entirely, which would make every assertion above pass
     * for the wrong reason.
     */
    private CachedLikeFileChat proxiedWith(CacheErrorHandler errorHandler, CachedLikeFileChatImpl target) {
        CacheInterceptor interceptor = new CacheInterceptor();
        interceptor.setCacheManager(new UnreachableRedisCacheManager());
        interceptor.setCacheOperationSources(new AnnotationCacheOperationSource());
        interceptor.setErrorHandler(errorHandler);
        interceptor.afterPropertiesSet();
        interceptor.afterSingletonsInstantiated();
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return (CachedLikeFileChat) factory.getProxy();
    }

    /** Shaped like the two real call sites: one @Cacheable read, one allEntries eviction. */
    public interface CachedLikeFileChat {
        String metadataFor(String bucket, String key);
        void deleteAndEvict(String bucket);
    }

    public static class CachedLikeFileChatImpl implements CachedLikeFileChat {

        final AtomicInteger realInvocations = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();

        @Override
        @Cacheable(value = "fileChatMetadata", unless = "#result == null")
        public String metadataFor(String bucket, String key) {
            this.realInvocations.incrementAndGet();
            return "etag-of-" + bucket + "/" + key;
        }

        @Override
        @CacheEvict(value = {"fileChatExtract", "fileChatMetadata"}, allEntries = true)
        public void deleteAndEvict(String bucket) {
            this.deletes.incrementAndGet();
        }
    }

    private static final class UnreachableRedisCacheManager implements CacheManager {

        @Override
        public Cache getCache(String name) {
            return new UnreachableRedisCache(name);
        }

        @Override
        public Collection<String> getCacheNames() {
            return Arrays.asList("fileChatExtract", "fileChatMetadata");
        }
    }

    /** Every operation raises what Lettuce raises when Redis is not answering. */
    private static final class UnreachableRedisCache implements Cache {

        private final String name;

        UnreachableRedisCache(String name) {
            this.name = name;
        }

        private RuntimeException down() {
            return new RedisConnectionFailureException("Unable to connect to Redis");
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public Object getNativeCache() {
            return this;
        }

        @Override
        public ValueWrapper get(Object key) {
            throw this.down();
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            throw this.down();
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            throw this.down();
        }

        @Override
        public void put(Object key, Object value) {
            throw this.down();
        }

        @Override
        public void evict(Object key) {
            throw this.down();
        }

        @Override
        public void clear() {
            throw this.down();
        }
    }

}
