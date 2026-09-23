package process.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Publishes platform_outbox to Kafka, in the order the events were written (MIG-22).
 *
 * One drainer at a time, across every instance: each batch runs under a transaction-scoped
 * advisory lock, so a job's Queue, Start and Completed can never be sent by two instances at once
 * and overtake one another. Within a batch each send is waited for before the next, and a send the
 * broker refuses stops the batch -- nothing behind it goes out first -- to be retried on the next
 * pass.
 *
 * It runs on its own thread, not on the shared @Scheduled pool, so a long cron cannot hold up live
 * pushes; and OutboxWriter wakes it after every commit, so the poll is only the fallback.
 *
 * @author Nabeel Ahmed
 */
public class OutboxRelay implements SmartLifecycle {

    /** Any fixed number; it only has to be the same on every instance. */
    static final long DRAIN_LOCK = 0x0B0C_5E1A_7E0AL;
    static final int BATCH = 100;
    static final int RETENTION_DAYS = 7;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KafkaTemplate<String, String> kafka;
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private volatile ScheduledExecutorService thread;
    private long pollMillis = 1000;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transaction, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.kafka = kafka;
    }

    void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    /** Publishes everything pending that this instance can take now; answers how many went out. */
    public int drain() {
        int published = 0;
        while (true) {
            int[] batch = this.transaction.execute(status -> this.batch());
            published += batch[0];
            if (batch[1] == 0) {
                return published;
            }
        }
    }

    /** {published, keepGoing}. */
    private int[] batch() {
        Boolean locked = this.jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, DRAIN_LOCK);
        if (!Boolean.TRUE.equals(locked)) {
            return new int[] {0, 0};
        }
        List<Object[]> rows = this.jdbc.query("SELECT outbox_id, topic, message_key, event FROM platform_outbox "
            + "WHERE published_at IS NULL ORDER BY outbox_id LIMIT ?",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)}, BATCH);
        List<Long> sent = new ArrayList<>();
        boolean refused = false;
        for (Object[] row : rows) {
            try {
                this.kafka.send((String) row[1], (String) row[2], (String) row[3]).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                sent.add((Long) row[0]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                refused = true;
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException failed) {
                Throwable cause = failed instanceof ExecutionException && failed.getCause() != null ? failed.getCause() : failed;
                String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                this.jdbc.update("UPDATE platform_outbox SET attempts = attempts + 1, last_error = ? WHERE outbox_id = ?",
                    reason.length() > 2000 ? reason.substring(0, 2000) : reason, row[0]);
                this.logger.warn("Could not publish outbox event {} to {}; it goes first on the next pass: {}", row[0], row[1], reason);
                refused = true;
                break;
            }
        }
        if (!sent.isEmpty()) {
            this.jdbc.update("UPDATE platform_outbox SET published_at = now() WHERE outbox_id IN ("
                + sent.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");
        }
        return new int[] {sent.size(), !refused && rows.size() == BATCH ? 1 : 0};
    }

    /** Deletes published events past their retention; answers how many. */
    public int purgePublished() {
        return this.jdbc.update("DELETE FROM platform_outbox WHERE published_at < now() - make_interval(days => ?)", RETENTION_DAYS);
    }

    /** Called after a commit that wrote to the outbox. Wakes coalesce: one pending drain covers them all. */
    public void wake() {
        ScheduledExecutorService running = this.thread;
        if (running != null && this.wakePending.compareAndSet(false, true)) {
            running.execute(this::drainQuietly);
        }
    }

    private void drainQuietly() {
        this.wakePending.set(false);
        try {
            this.drain();
        } catch (RuntimeException failed) {
            // The database or the lock query failed; the next wake or poll tries again.
            this.logger.warn("Outbox drain failed: {}", failed.getMessage());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread relay = new Thread(runnable, "outbox-relay");
            relay.setDaemon(true);
            return relay;
        });
        started.scheduleWithFixedDelay(this::drainQuietly, this.pollMillis, this.pollMillis, TimeUnit.MILLISECONDS);
        started.scheduleWithFixedDelay(() -> {
            try {
                int purged = this.purgePublished();
                if (purged > 0) this.logger.info("Purged {} published outbox events older than {} days.", purged, RETENTION_DAYS);
            } catch (RuntimeException failed) {
                this.logger.warn("Outbox purge failed: {}", failed.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);
        this.thread = started;
    }

    @Override
    public void stop() {
        ScheduledExecutorService running = this.thread;
        this.thread = null;
        if (running != null) {
            running.shutdown();
            try {
                running.awaitTermination(SEND_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return this.thread != null;
    }
}
