package process.filechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * File Chat's index lock, held in Redis so every replica of process takes the same one (MIG-111).
 *
 * It replaces a ConcurrentHashMap of monitors in FileChatServiceImpl, which serialised indexing
 * inside one JVM and did nothing across two -- two replicas both transcribed the same audio file --
 * and which grew by one entry per distinct file version for the life of the process.
 *
 * One key per file version, prefix + bucket|key|etag, whose value is the holder's own random token
 * and whose expiry is the lease. One Lua script does all three things a holder does to it --
 * take, renew, give back -- and each compares the token first, so nobody renews or deletes a lock
 * that has passed to someone else. The lease runs on Redis's own clock (PX), never a JVM's.
 *
 * The lease is short (thirty seconds by default) and a live holder renews it every third of that
 * from one heartbeat thread, for as long as its extraction runs: a transcription is minutes, and
 * the lease is not how long a holder may work but how long a DEAD one blocks the file. When the JVM
 * holding it dies mid-extraction nothing is released, the renewals stop, and the key expires; the
 * next caller then takes it, re-checks the index, and does the work the dead one never finished.
 *
 * A waiter asks again every {@link #POLL} until the configured wait is over, then gives up with
 * {@link FileIndexLock.Busy} -- the holder is alive and still extracting, and a second extraction is
 * exactly the cost this lock exists to save.
 *
 * When Redis cannot be asked, {@link FileIndexLock.Unavailable}: never a lock that says yes without
 * asking. The caller then indexes nothing.
 *
 * Bounded both sides: the JVM keeps only the locks it holds right now (for the heartbeat), and
 * Redis only the keys somebody holds -- each is deleted on release, and expires with its lease if
 * the release never comes.
 *
 * @author Nabeel Ahmed
 */
@Component
public class RedisFileIndexLock implements FileIndexLock, DisposableBean {

    static final String DEFAULT_PREFIX = "filechat:index-lock:";
    static final Duration POLL = Duration.ofMillis(200);

    private static final Logger logger = LoggerFactory.getLogger(RedisFileIndexLock.class);

    /**
     * KEYS[1] the file version's lock; ARGV op, token, lease in ms. Returns 1 when, afterwards, the
     * token holds the lock with a fresh lease (acquire, renew), or when release removed it; else 0.
     */
    private static final RedisScript<Long> LOCK = new DefaultRedisScript<>(
        "local op = ARGV[1]\n"
            + "if op == 'acquire' then\n"
            + "  if redis.call('SET', KEYS[1], ARGV[2], 'NX', 'PX', tonumber(ARGV[3])) then return 1 end\n"
            + "end\n"
            + "if redis.call('GET', KEYS[1]) ~= ARGV[2] then return 0 end\n"
            + "if op == 'release' then redis.call('DEL', KEYS[1]) return 1 end\n"
            + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]))\n"
            + "return 1", Long.class);

    private final RedisTemplate<String, String> redis;
    private final String prefix;
    private final Duration lease;
    private final Duration wait;
    private final ScheduledExecutorService heartbeat;
    /** The locks this JVM holds right now, by Redis key, each with its token. Emptied on release. */
    private final Map<String, String> held = new ConcurrentHashMap<>();

    @Autowired
    public RedisFileIndexLock(@Qualifier("redisTemplate") RedisTemplate<String, String> redis,
        @Value("${filechat.index-lock.key-prefix:" + DEFAULT_PREFIX + "}") String prefix,
        @Value("${filechat.index-lock.lease-seconds:30}") long leaseSeconds,
        @Value("${filechat.index-lock.wait-seconds:120}") long waitSeconds) {
        this(redis, prefix, Duration.ofSeconds(leaseSeconds), Duration.ofSeconds(waitSeconds),
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "filechat-index-lock-heartbeat");
                thread.setDaemon(true);
                return thread;
            }));
    }

    public RedisFileIndexLock(RedisTemplate<String, String> redis, String prefix, Duration lease, Duration wait,
        ScheduledExecutorService heartbeat) {
        this.redis = redis;
        this.prefix = prefix;
        this.lease = lease;
        this.wait = wait;
        this.heartbeat = heartbeat;
        long every = Math.max(1, lease.toMillis() / 3);
        this.heartbeat.scheduleWithFixedDelay(this::renewAll, every, every, TimeUnit.MILLISECONDS);
    }

    @Override
    public Held acquire(String bucket, String key, String etag) throws Busy, InterruptedException {
        if (etag == null || etag.isEmpty()) {
            // DEF-038: a store that returns no etag has no file version to lock or index under.
            throw new IllegalArgumentException("A file with no etag has no index lock: " + bucket + "/" + key);
        }
        String lockKey = this.keyFor(bucket, key, etag);
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + this.wait.toNanos();
        while (true) {
            if (this.run("acquire", lockKey, token)) {
                this.held.put(lockKey, token);
                return this.heldAs(lockKey, token);
            }
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMs <= 0) {
                throw new Busy("Another request is still preparing " + bucket + "/" + key
                    + " (etag " + etag + ") after " + this.wait.getSeconds() + "s");
            }
            Thread.sleep(Math.min(POLL.toMillis(), leftMs));
        }
    }

    private Held heldAs(String lockKey, String token) {
        AtomicBoolean closed = new AtomicBoolean();
        return new Held() {
            @Override
            public boolean stillHeld() {
                // A renewal is the question asked of Redis itself: it succeeds only for the holder.
                return !closed.get() && RedisFileIndexLock.this.run("renew", lockKey, token);
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                RedisFileIndexLock.this.held.remove(lockKey, token);
                try {
                    RedisFileIndexLock.this.run("release", lockKey, token);
                } catch (Unavailable unreachable) {
                    // The lease gives it back; until then the file waits, it is never indexed twice.
                    logger.warn("File Chat: could not release the index lock {}; its lease will: {}",
                        lockKey, unreachable.getMessage());
                }
            }
        };
    }

    /** One heartbeat for every lock this JVM holds. A lock found lost is dropped, not re-taken. */
    private void renewAll() {
        for (Map.Entry<String, String> lock : this.held.entrySet()) {
            try {
                if (!this.run("renew", lock.getKey(), lock.getValue())) {
                    logger.warn("File Chat: the index lock {} was lost before it was released "
                        + "(a pause longer than its lease?); its holder will not write.", lock.getKey());
                    this.held.remove(lock.getKey(), lock.getValue());
                }
            } catch (RuntimeException unreachable) {
                logger.warn("File Chat: could not renew the index lock {}: {}", lock.getKey(), unreachable.getMessage());
            }
        }
    }

    private boolean run(String op, String lockKey, String token) {
        List<String> keys = Collections.singletonList(lockKey);
        Long answer;
        try {
            answer = this.redis.execute(LOCK, keys, op, token, String.valueOf(this.lease.toMillis()));
        } catch (RuntimeException unreachable) {
            throw new Unavailable("The File Chat index lock in Redis could not be reached", unreachable);
        }
        return answer != null && answer == 1L;
    }

    String keyFor(String bucket, String key, String etag) {
        return this.prefix + bucket + "|" + key + "|" + etag;
    }

    int heldCount() {
        return this.held.size();
    }

    ScheduledExecutorService heartbeat() {
        return this.heartbeat;
    }

    @Override
    public void destroy() {
        this.heartbeat.shutdownNow();
    }
}
