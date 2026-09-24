package process.security;

import org.barco.platform.cache.SharedCacheVersion;
import org.barco.platform.cache.VersionStore;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;

import org.springframework.stereotype.Component;
import process.model.enums.PageKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A person's resolved page set, remembered for a few seconds.
 *
 * Its own bean rather than a field of the interceptor, because the things that change the
 * answer -- a profile edited, a person moved to another profile -- live in services the
 * interceptor depends on, and a bean cannot be told about a change by something it created.
 * Both sides hold this instead.
 *
 * @author Nabeel Ahmed
 */
@Component
public class PageAccessCache {

    /** How long a resolved set is trusted. Short: a change should land before it is explained. */
    static final long TTL_MILLIS = 15_000L;

    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    private final LongSupplier clock;

    /** How often an instance checks the shared version (MIG-110). */
    static final Duration POLL = Duration.ofSeconds(2);

    /** Null on one instance on its own; otherwise the version every instance's changes move. */
    private final SharedCacheVersion version;

    public PageAccessCache() {
        this(System::currentTimeMillis);
    }

    /** A clock of the caller's, so the TTL bound can be asserted to the millisecond (MIG-99, T4). */
    PageAccessCache(LongSupplier clock) {
        this(null, clock);
    }

    /**
     * MIG-110: a change made through any instance empties every instance's answers at its next poll
     * (POLL, 2 s); the 15-second TTL stays as the bound when Redis cannot be reached.
     */
    @Autowired
    public PageAccessCache(VersionStore versions) {
        this(versions, System::currentTimeMillis);
    }

    PageAccessCache(VersionStore versions, LongSupplier clock) {
        this.clock = clock;
        this.version = versions == null ? null
            : new SharedCacheVersion(versions, "page-access", this.entries::clear, Duration.ofMillis(TTL_MILLIS));
        if (this.version != null) {
            this.version.initialise();
        }
    }

    @PostConstruct
    public void start() {
        if (this.version != null) {
            this.version.start(POLL);
        }
    }

    @PreDestroy
    public void stop() {
        if (this.version != null) {
            this.version.close();
        }
    }

    /** One check of the shared version; the poller calls it every POLL. */
    void poll() {
        if (this.version != null) {
            this.version.poll();
        }
    }

    public Set<PageKey> get(Long appUserId, Function<Long, Set<PageKey>> resolve) {
        long now = this.clock.getAsLong();
        Entry entry = this.entries.get(appUserId);
        if (entry != null && entry.expiresAt > now) {
            return entry.pages;
        }
        Set<PageKey> pages = resolve.apply(appUserId);
        this.entries.put(appUserId, new Entry(pages, now + TTL_MILLIS));
        return pages;
    }

    /** One person moved to another profile. Every instance forgets (after commit, in a transaction). */
    public void forget(Long appUserId) {
        if (appUserId == null) {
            return;
        }
        if (this.version == null) {
            this.entries.remove(appUserId);
        } else {
            this.version.changed();
        }
    }

    /**
     * A profile's pages changed, so every holder's answer did: every process instance empties its
     * answers at its next poll (MIG-110). The gateway's page gate keeps its own for up to the
     * 15-second TTL (ADR-019).
     */
    public void forgetAll() {
        if (this.version == null) {
            this.entries.clear();
        } else {
            this.version.changed();
        }
    }

    private static final class Entry {
        final Set<PageKey> pages;
        final long expiresAt;
        Entry(Set<PageKey> pages, long expiresAt) { this.pages = pages; this.expiresAt = expiresAt; }
    }
}
