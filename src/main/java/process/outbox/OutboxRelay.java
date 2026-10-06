package process.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.correlation.CorrelationScope;
import org.barco.platform.outbox.OutboxBatch;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Publishes platform_outbox to Kafka, in the order the events were written (MIG-22).
 *
 * One drainer at a time, across every instance: each batch runs under a transaction-scoped
 * advisory lock, so a job's Queue, Start and Completed can never be sent by two instances at once
 * and overtake one another. A batch is handed to the (idempotent) producer at once and its answers
 * waited for together (platform-commons OutboxBatch), so one key's events keep their order through
 * retries. A row the broker refused for now goes first on the next pass; one that can never be sent --
 * too large, an impossible topic, or its tenth failure while the broker answers -- is parked (dead_at),
 * logged at ERROR with its event id and topic, and the rows behind it go on (event audit E2). A parked
 * row is sent again once someone clears its dead_at. A broker out of reach parks nothing.
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
    private static final ObjectMapper EVENTS = new ObjectMapper();
    static final int RETENTION_DAYS = 7;
    /** How long one pass waits for the broker to answer for its whole batch. */
    private static final long SEND_WAIT_MILLIS = 30_000;

    private final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KafkaTemplate<String, String> kafka;
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicLong dead = new AtomicLong();
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
        List<OutboxBatch.Row> rows = this.jdbc.query("SELECT outbox_id, event_id, topic, message_key, event, attempts FROM platform_outbox"
            + " WHERE published_at IS NULL AND dead_at IS NULL ORDER BY outbox_id LIMIT ?", (rs, i) -> new OutboxBatch.Row(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)), BATCH);
        List<Long> sent = new ArrayList<>();
        boolean retry = false;
        for (OutboxBatch.Outcome outcome : OutboxBatch.send(rows, this::send, SEND_WAIT_MILLIS)) {
            OutboxBatch.Row row = outcome.getRow();
            if (outcome.getFate() == OutboxBatch.Fate.SENT) {
                sent.add(row.getId());
                continue;
            }
            try (CorrelationScope scope = CorrelationScope.open(traceIdOf(row.getEvent()))) {
                if (outcome.getFate() == OutboxBatch.Fate.DEAD) {
                    this.jdbc.update("UPDATE platform_outbox SET attempts = attempts + 1, last_error = ?, dead_at = now() WHERE outbox_id = ?",
                        outcome.getReason(), row.getId());
                    this.dead.incrementAndGet();
                    this.logger.error("Parked outbox event {} (row {}, topic {}) as dead after {} attempt(s); the events behind it go on: {}",
                        row.getEventId(), row.getId(), row.getTopic(), row.getAttempts() + 1, outcome.getReason());
                } else {
                    this.jdbc.update("UPDATE platform_outbox SET attempts = attempts + 1, last_error = ? WHERE outbox_id = ?",
                        outcome.getReason(), row.getId());
                    this.logger.warn("Could not publish outbox event {} (row {}) to {}; it goes first on the next pass: {}", row.getEventId(),
                        row.getId(), row.getTopic(), outcome.getReason());
                    retry = true;
                }
            }
        }
        if (!sent.isEmpty()) {
            this.jdbc.update("UPDATE platform_outbox SET published_at = now() WHERE outbox_id IN ("
                + sent.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");
        }
        return new int[] {sent.size(), !retry && rows.size() == BATCH ? 1 : 0};
    }

    /**
     * Hands one row to the producer under the id it carries (its event's traceId, as the X-Correlation-Id header; none
     * when it has no usable one, X10); the answer is waited for with the rest of the batch.
     */
    private Future<?> send(OutboxBatch.Row row) {
        ProducerRecord<String, String> record = recordOf(row.getTopic(), row.getKey(), row.getEvent());
        Header traced = record.headers().lastHeader(CorrelationId.HEADER);
        try (CorrelationScope scope = CorrelationScope.open(traced == null ? null : CorrelationId.fromHeader(traced.value()))) {
            return this.kafka.send(record);
        }
    }

    /** Events parked as dead (dead_at): what the outbox health detail and the platform.outbox.dead gauge show. */
    public long deadCount() {
        return this.dead.get();
    }

    /** Counts the parked events again; the relay's thread does this every minute, so a requeue (dead_at = NULL) shows. */
    void refreshDeadCount() {
        Long parked = this.jdbc.queryForObject("SELECT count(*) FROM platform_outbox WHERE dead_at IS NOT NULL", Long.class);
        this.dead.set(parked == null ? 0 : parked);
    }

    /**
     * The record for one outbox row: the event as written, and its traceId -- the correlation id of the work that
     * raised it (PlatformEvent) -- as the X-Correlation-Id header, so a consumer reading headers finds it without
     * parsing the payload (X6). An event with no usable traceId goes out without the header; nothing here can stop
     * an event from being published (X10).
     */
    public static ProducerRecord<String, String> recordOf(String topic, String key, String event) {
        return recordOf(topic, key, event, traceIdOf(event));
    }

    /** A record with this id as its X-Correlation-Id header, when the id is usable. */
    public static ProducerRecord<String, String> recordOf(String topic, String key, String value, String correlationId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        if (CorrelationId.isAcceptable(correlationId)) {
            record.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    static String traceIdOf(String event) {
        try {
            JsonNode traceId = EVENTS.readTree(event).path("traceId");
            return traceId.isTextual() ? traceId.asText() : null;
        } catch (Exception notJson) {
            return null;
        }
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

    private void refreshDeadQuietly() {
        try (CorrelationScope tick = CorrelationScope.open(null)) {
            try {
                this.refreshDeadCount();
            } catch (RuntimeException failed) {
                this.logger.warn("Could not count the parked outbox events: {}", failed.getMessage());
            }
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
        started.scheduleWithFixedDelay(this::refreshDeadQuietly, 0, 1, TimeUnit.MINUTES);
        this.thread = started;
    }

    @Override
    public void stop() {
        ScheduledExecutorService running = this.thread;
        this.thread = null;
        if (running != null) {
            running.shutdown();
            try {
                running.awaitTermination(SEND_WAIT_MILLIS + 5_000, TimeUnit.MILLISECONDS);
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
