package process.security;

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

    public PageAccessCache() {
        this(System::currentTimeMillis);
    }

    /** A clock of the caller's, so the TTL bound can be asserted to the millisecond (MIG-99, T4). */
    PageAccessCache(LongSupplier clock) {
        this.clock = clock;
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

    /** One person moved to another profile. */
    public void forget(Long appUserId) {
        if (appUserId != null) {
            this.entries.remove(appUserId);
        }
    }

    /**
     * A profile's pages changed, so every holder's answer did -- on THIS instance. Other process
     * instances and the gateway's page gate keep their own answers until the 15-second TTL ends
     * them; that bound, not this method, is the cross-instance guarantee (ADR-019, MIG-99).
     */
    public void forgetAllHere() {
        this.entries.clear();
    }

    private static final class Entry {
        final Set<PageKey> pages;
        final long expiresAt;
        Entry(Set<PageKey> pages, long expiresAt) { this.pages = pages; this.expiresAt = expiresAt; }
    }
}
