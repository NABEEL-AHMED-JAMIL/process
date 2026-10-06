package process.filechat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-111: the File Chat index lock is one lock however many instances of process ask for it.
 *
 * Instances A and B are two RedisFileIndexLocks over one Redis, each with its own heartbeat, as two
 * replicas are. Real Redis, real threads, real time: the lease is short so its expiry is waited
 * for in hundreds of milliseconds, not the production thirty seconds.
 */
class RedisFileIndexLockTest {

    private static final Duration LEASE = Duration.ofMillis(600);

    private RedisFileIndexLocks locks;

    @BeforeEach
    void setUp() {
        this.locks = RedisFileIndexLocks.open();
    }

    @AfterEach
    void tearDown() {
        if (this.locks != null) this.locks.close();
    }

    @Test
    void twoInstancesExcludeEachOtherOnTheSameFileVersion() throws Exception {
        RedisFileIndexLock a = this.locks.instance(LEASE, Duration.ofMillis(300));
        RedisFileIndexLock b = this.locks.instance(LEASE, Duration.ofMillis(300));

        FileIndexLock.Held held = a.acquire("docs", "talk.mp3", "etag-1");
        assertThatThrownBy(() -> b.acquire("docs", "talk.mp3", "etag-1"))
            .as("B waits out its whole wait while A holds the lock, and is told so")
            .isInstanceOf(FileIndexLock.Busy.class);

        held.close();
        try (FileIndexLock.Held mine = b.acquire("docs", "talk.mp3", "etag-1")) {
            assertThat(mine.stillHeld()).as("released by A, taken by B").isTrue();
        }
    }

    /** Many threads across two instances: never two holders at once, and every one gets its turn. */
    @Test
    void neverTwoHoldersAtOnceAcrossInstancesAndThreads() throws Exception {
        RedisFileIndexLock a = this.locks.instance(LEASE, Duration.ofSeconds(20));
        RedisFileIndexLock b = this.locks.instance(LEASE, Duration.ofSeconds(20));
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostInsideAtOnce = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] work = new Future<?>[8];
            for (int i = 0; i < work.length; i++) {
                RedisFileIndexLock mine = i % 2 == 0 ? a : b;
                work[i] = pool.submit(() -> {
                    start.await();
                    try (FileIndexLock.Held held = mine.acquire("docs", "talk.mp3", "etag-1")) {
                        int now = inside.incrementAndGet();
                        mostInsideAtOnce.accumulateAndGet(now, Math::max);
                        Thread.sleep(30);
                        inside.decrementAndGet();
                        turns.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> f : work) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(mostInsideAtOnce.get()).as("one holder at a time, across both instances").isEqualTo(1);
        assertThat(turns.get()).isEqualTo(8);
    }

    /** The etag is part of the identity: a changed file takes a different lock, and so does another file. */
    @Test
    void anotherFileOrAnotherVersionTakesADifferentLock() throws Exception {
        RedisFileIndexLock a = this.locks.instance(LEASE, Duration.ofMillis(200));
        RedisFileIndexLock b = this.locks.instance(LEASE, Duration.ofMillis(200));
        try (FileIndexLock.Held held = a.acquire("docs", "talk.mp3", "etag-1");
             FileIndexLock.Held newVersion = b.acquire("docs", "talk.mp3", "etag-2");
             FileIndexLock.Held otherKey = b.acquire("docs", "other.mp3", "etag-1");
             FileIndexLock.Held otherBucket = b.acquire("media", "talk.mp3", "etag-1")) {
            assertThat(newVersion.stillHeld()).isTrue();
            assertThat(otherKey.stillHeld()).isTrue();
            assertThat(otherBucket.stillHeld()).isTrue();
        }
        assertThat(a.keyFor("docs", "talk.mp3", "etag-1")).isEqualTo(this.locks.prefix() + "docs|talk.mp3|etag-1");
    }

    /**
     * A live holder keeps its lock past the lease however long the extraction runs: the heartbeat
     * renews it. An audio transcription is minutes; the lease is only how long a DEAD holder blocks.
     */
    @Test
    void aLiveHolderKeepsItsLockLongPastOneLease() throws Exception {
        RedisFileIndexLock a = this.locks.instance(LEASE, Duration.ofMillis(200));
        RedisFileIndexLock b = this.locks.instance(LEASE, Duration.ofMillis(200));
        try (FileIndexLock.Held held = a.acquire("docs", "talk.mp3", "etag-1")) {
            Thread.sleep(LEASE.toMillis() * 3);
            assertThat(held.stillHeld()).isTrue();
            assertThatThrownBy(() -> b.acquire("docs", "talk.mp3", "etag-1")).isInstanceOf(FileIndexLock.Busy.class);
        }
    }

    /**
     * A holder whose JVM dies mid-extraction releases nothing. Its lock comes back when the lease
     * runs out, and the next holder gets it; the dead one, were it ever to wake, knows it lost it and
     * cannot give away the new holder's lock by closing its own.
     */
    @Test
    void aCrashedHoldersLockComesBackWhenItsLeaseRunsOut() throws Exception {
        RedisFileIndexLock dying = this.locks.instance(LEASE, Duration.ofMillis(200));
        RedisFileIndexLock survivor = this.locks.instance(LEASE, Duration.ofSeconds(5));

        FileIndexLock.Held orphan = dying.acquire("docs", "talk.mp3", "etag-1");
        this.locks.crash(dying);

        long started = System.nanoTime();
        try (FileIndexLock.Held recovered = survivor.acquire("docs", "talk.mp3", "etag-1")) {
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(waitedMs).as("recovered by the lease, not before it and not long after")
                .isBetween(LEASE.toMillis() / 3, LEASE.toMillis() + 2_000);
            assertThat(orphan.stillHeld()).as("the dead holder has lost it").isFalse();

            orphan.close();
            assertThat(recovered.stillHeld()).as("a late close by the dead holder frees nothing of the new one's")
                .isTrue();
        }
    }

    /**
     * The in-memory map this replaces grew by one entry per distinct file version for the life of
     * the JVM. Nothing here does: after a hundred distinct files, neither the JVM nor Redis holds
     * anything for any of them.
     */
    @Test
    void nothingIsLeftBehindPerDistinctFile() throws Exception {
        RedisFileIndexLock a = this.locks.instance(LEASE, Duration.ofMillis(200));
        for (int i = 0; i < 100; i++) {
            try (FileIndexLock.Held held = a.acquire("docs", "file-" + i + ".mp3", "etag-" + i)) {
                assertThat(a.heldCount()).isEqualTo(1);
            }
        }
        assertThat(a.heldCount()).as("no per-file state in the JVM").isZero();
        assertThat(this.locks.keysLeft()).as("no per-file key in Redis").isEmpty();
    }

    /** No Redis, no lock -- and never a lock that says yes without asking. */
    @Test
    void anUnreachableRedisIsReportedNotTreatedAsFree() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory("localhost", 1);
        nowhere.afterPropertiesSet();
        try {
            RedisFileIndexLock lock = new RedisFileIndexLock(RedisFileIndexLocks.template(nowhere), "x:",
                LEASE, Duration.ofMillis(200), Executors.newSingleThreadScheduledExecutor());
            assertThatThrownBy(() -> lock.acquire("docs", "talk.mp3", "etag-1"))
                .isInstanceOf(FileIndexLock.Unavailable.class);
        } finally {
            nowhere.destroy();
        }
    }

    /** DEF-038: a store that hands back no etag has no file version to lock. */
    @Test
    void aFileWithNoEtagHasNoLock() {
        RedisFileIndexLock a = this.locks.instance(LEASE, Duration.ofMillis(200));
        assertThatThrownBy(() -> a.acquire("docs", "talk.mp3", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
