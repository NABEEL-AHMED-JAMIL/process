package process.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes an event to platform_outbox in the caller's transaction (MIG-22).
 *
 * It goes through the same DataSource as the JPA work around it, so the row commits or rolls back
 * with the state change it announces. After the commit it wakes the relay, so a push is not left
 * waiting for the next poll.
 *
 * @author Nabeel Ahmed
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private volatile Runnable wakeRelay = () -> { };

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void wakeOnCommit(Runnable wakeRelay) {
        this.wakeRelay = wakeRelay;
    }

    public void write(String topic, String messageKey, String eventId, String event) {
        this.jdbc.update("INSERT INTO platform_outbox (event_id, topic, message_key, event) VALUES (?, ?, ?, ?)",
            eventId, topic, messageKey, event);
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
