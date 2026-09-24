package process.outbox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes dispatch_outbox to each run's worker queue, after the dispatch that wrote it has committed
 * (V87, MIG-136).
 *
 * Each row goes to the broker its tenant and task type resolve to, as the dispatcher's own send did,
 * with its headers -- x-tenant-id, x-user-id and X-Correlation-Id (MIG-26, MIG-95). The outcome is
 * recorded in one transaction with the row: published (the run moves to Start, and the payload -- which
 * carries the run's callback token -- is wiped), or refused (the run is closed as a transient failure,
 * or offered another attempt, exactly as the in-pass send's failure callback did).
 *
 * Rows are claimed with a short lease, FOR UPDATE SKIP LOCKED, so any number of instances drain
 * together without sending a row twice. A broker that refuses one row leaves that connection's other
 * rows alone until their lease runs out, rather than paying the send timeout on each of them in turn
 * while every other tenant's runs wait. It runs on its own thread, never the @Scheduled pool, and
 * DispatchOutbox wakes it after every commit, so the poll is only the fallback.
 *
 * @author Nabeel Ahmed
 */
public class DispatchRelay implements SmartLifecycle {

    static final int BATCH = 50;
    static final long SEND_TIMEOUT_SECONDS = 10;

    private static final Gson GSON = new Gson();

    private final Logger logger = LoggerFactory.getLogger(DispatchRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final KafkaConnectionResolver kafkaConnectionResolver;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private volatile DispatchOutcomes outcomes;
    private volatile ScheduledExecutorService thread;
    private long pollMillis = 1000;

    public DispatchRelay(JdbcTemplate jdbc, TransactionOperations transactions,
        KafkaConnectionResolver kafkaConnectionResolver, KafkaTemplateProvider kafkaTemplateProvider) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
    }

    void setOutcomes(DispatchOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    /** One claimed row. */
    static final class Row {

        long outboxId;
        long jobQueueId;
        int attempt;
        Long tenantId;
        Long sourceTaskTypeId;
        String topic;
        Integer partition;
        String messageKey;
        String payload;
        String headers;

        String connection() {
            return this.tenantId + "/" + this.sourceTaskTypeId;
        }
    }

    /** Publishes everything this instance can take now; answers how many went out. */
    public int drain() {
        int published = 0;
        Set<String> refused = new HashSet<>();
        while (true) {
            List<Row> rows = this.claim();
            if (rows.isEmpty()) {
                return published;
            }
            for (Row row : rows) {
                if (refused.contains(row.connection())) {
                    // Left claimed: its lease runs out and a later drain tries the broker again.
                    continue;
                }
                if (this.publish(row)) {
                    published++;
                } else {
                    refused.add(row.connection());
                }
                if (Thread.currentThread().isInterrupted()) {
                    return published;
                }
            }
        }
    }

    private List<Row> claim() {
        List<Row> rows = this.transactions.execute(status -> this.jdbc.query(
            "UPDATE dispatch_outbox SET claimed_until = now() + interval '2 minutes' WHERE outbox_id IN ("
                + "SELECT outbox_id FROM dispatch_outbox WHERE published_at IS NULL AND abandoned_at IS NULL "
                + "AND (claimed_until IS NULL OR claimed_until < now()) ORDER BY outbox_id LIMIT ? FOR UPDATE SKIP LOCKED) "
                + "RETURNING outbox_id, job_queue_id, attempt, tenant_id, source_task_type_id, topic, topic_partition, "
                + "message_key, payload, headers",
            (rs, i) -> {
                Row row = new Row();
                row.outboxId = rs.getLong(1);
                row.jobQueueId = rs.getLong(2);
                row.attempt = rs.getInt(3);
                row.tenantId = (Long) rs.getObject(4);
                row.sourceTaskTypeId = (Long) rs.getObject(5);
                row.topic = rs.getString(6);
                row.partition = (Integer) rs.getObject(7);
                row.messageKey = rs.getString(8);
                row.payload = rs.getString(9);
                row.headers = rs.getString(10);
                return row;
            }, BATCH));
        List<Row> ordered = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
        ordered.sort(Comparator.comparingLong(row -> row.outboxId));
        return ordered;
    }

    /** True when the broker took the row. Either way the outcome is recorded before this returns. */
    private boolean publish(Row row) {
        try {
            KafkaTemplate<String, String> template = this.kafkaTemplateProvider.getTemplate(
                this.kafkaConnectionResolver.resolve(row.tenantId, row.sourceTaskTypeId));
            SendResult<String, String> sent = template.send(new ProducerRecord<>(row.topic, row.partition, row.messageKey,
                row.payload, headersOf(row.headers))).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long offset = sent.getRecordMetadata().offset();
            this.transactions.execute(status -> {
                this.jdbc.update("UPDATE dispatch_outbox SET published_at = now(), record_offset = ?, payload = NULL, "
                    + "claimed_until = NULL, attempts = attempts + 1 WHERE outbox_id = ?", offset, row.outboxId);
                this.outcomes.published(row.jobQueueId, row.attempt, offset);
                return null;
            });
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failed) {
            Throwable cause = failed instanceof ExecutionException && failed.getCause() != null ? failed.getCause() : failed;
            String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            this.logger.warn("Could not hand run {} (attempt {}) to {}: {}", row.jobQueueId, row.attempt, row.topic, reason);
            this.transactions.execute(status -> {
                this.jdbc.update("UPDATE dispatch_outbox SET abandoned_at = now(), payload = NULL, claimed_until = NULL, "
                    + "attempts = attempts + 1, last_error = ? WHERE outbox_id = ?",
                    reason.length() > 2000 ? reason.substring(0, 2000) : reason, row.outboxId);
                this.outcomes.publishFailed(row.jobQueueId, row.attempt, cause);
                return null;
            });
            return false;
        }
    }

    static List<Header> headersOf(String json) {
        Map<String, String> map = GSON.fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
        List<Header> headers = new ArrayList<>();
        if (map != null) {
            map.forEach((name, value) -> {
                if (value != null) {
                    headers.add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
                }
            });
        }
        return headers;
    }

    /** Called after a commit that wrote to dispatch_outbox. Wakes coalesce: one pending drain covers them all. */
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
            // The database failed; the next wake or poll tries again.
            this.logger.warn("Dispatch outbox drain failed: {}", failed.getMessage());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread relay = new Thread(runnable, "dispatch-relay");
            relay.setDaemon(true);
            return relay;
        });
        started.scheduleWithFixedDelay(this::drainQuietly, this.pollMillis, this.pollMillis, TimeUnit.MILLISECONDS);
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
