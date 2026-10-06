package process.outbox;

import com.google.gson.Gson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Writes a run's hand-off to its worker queue to dispatch_outbox, in the caller's transaction (V87,
 * MIG-136).
 *
 * The dispatcher writes it in the same local transaction as the run's callback token hash and its
 * job_send latch, so the three commit or roll back together: a rolled-back dispatch can no longer have
 * already been published, and a published run always exists. After the commit it wakes DispatchRelay,
 * so the hand-off is not left waiting for the next poll.
 *
 * @author Nabeel Ahmed
 */
@Component
public class DispatchOutbox {

    /** One run's message, as DispatchRelay will publish it. */
    public static final class Record {

        public final long jobQueueId;
        public final int attempt;
        public final Long tenantId;
        public final Long sourceTaskTypeId;
        public final String topic;
        /** Null for "any partition". */
        public final Integer partition;
        public final String messageKey;
        public final String payload;
        public final Map<String, String> headers;

        public Record(long jobQueueId, int attempt, Long tenantId, Long sourceTaskTypeId, String topic, Integer partition,
            String messageKey, String payload, Map<String, String> headers) {
            this.jobQueueId = jobQueueId;
            this.attempt = attempt;
            this.tenantId = tenantId;
            this.sourceTaskTypeId = sourceTaskTypeId;
            this.topic = topic;
            this.partition = partition;
            this.messageKey = messageKey;
            this.payload = payload;
            this.headers = headers;
        }
    }

    private final JdbcTemplate jdbc;
    private volatile Runnable wakeRelay = () -> { };

    public DispatchOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void wakeOnCommit(Runnable wakeRelay) {
        this.wakeRelay = wakeRelay;
    }

    public void write(Record record) {
        this.jdbc.update("INSERT INTO dispatch_outbox (job_queue_id, attempt, tenant_id, source_task_type_id, topic, "
            + "topic_partition, message_key, payload, headers) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            record.jobQueueId, record.attempt, record.tenantId, record.sourceTaskTypeId, record.topic, record.partition,
            record.messageKey, record.payload, new Gson().toJson(record.headers));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            final Runnable wake = this.wakeRelay;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake.run();
                }
            });
        } else {
            this.wakeRelay.run();
        }
    }
}
