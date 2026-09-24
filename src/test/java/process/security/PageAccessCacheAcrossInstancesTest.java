package process.security;

import org.junit.jupiter.api.Test;
import process.model.enums.PageKey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-99, T4: per-instance duplication, bounded by the TTL (ADR-019). Three instances read one
 * database. A revocation made through A is seen on A at once (it forgets its own entry), and on
 * every other instance no later than 15 seconds after they last read it -- the bound is the TTL,
 * not "eventually". The clock is the test's, so the boundary is asserted to the millisecond.
 */
class PageAccessCacheAcrossInstancesTest {

    private static final long OLIVIA = 44L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Map<Long, Set<PageKey>> database = new ConcurrentHashMap<>();

    private Set<PageKey> read(Long appUserId) {
        return EnumSet.copyOf(this.database.get(appUserId));
    }

    @Test
    void aRevocationOnOneInstanceIsSeenOnEveryInstanceWithinTheTtl() {
        this.database.put(OLIVIA, EnumSet.of(PageKey.JOBS, PageKey.BILLING));
        List<PageAccessCache> instances = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            instances.add(new PageAccessCache(this.now::get));
        }
        PageAccessCache a = instances.get(0);
        long readAt = this.now.get();
        instances.forEach(cache -> assertThat(cache.get(OLIVIA, this::read)).contains(PageKey.BILLING));

        // The profile edit lands on A: the database changes and A forgets its own entry.
        this.now.addAndGet(2_000);
        this.database.put(OLIVIA, EnumSet.of(PageKey.JOBS));
        a.forget(OLIVIA);

        assertThat(a.get(OLIVIA, this::read)).as("the instance that made the change").doesNotContain(PageKey.BILLING);

        // The others may still answer from what they read, but never past the TTL of that read.
        this.now.set(readAt + PageAccessCache.TTL_MILLIS - 1);
        for (PageAccessCache other : instances.subList(1, 3)) {
            assertThat(other.get(OLIVIA, this::read)).as("stale, inside the TTL").contains(PageKey.BILLING);
        }
        this.now.set(readAt + PageAccessCache.TTL_MILLIS);
        for (PageAccessCache every : instances) {
            assertThat(every.get(OLIVIA, this::read)).as("at the TTL, on every instance").doesNotContain(PageKey.BILLING);
        }
    }

    /** The bound the whole posture rests on; lengthening it is a decision, not a tweak. */
    @Test
    void theTtlIsFifteenSeconds() {
        assertThat(PageAccessCache.TTL_MILLIS).isEqualTo(15_000L);
    }
}
