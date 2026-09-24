package process.callback;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * worker_callback_receipt, through the same DataSource as the JPA work around it, so a claim commits or
 * rolls back with the writes it guards (V80).
 *
 * ON CONFLICT DO NOTHING is what makes a race safe rather than a matter of luck: Postgres holds the
 * second insert on the primary key until the first transaction ends, then reports it as a conflict if
 * the first committed and lets it through if the first rolled back. Times are the application's clock,
 * passed in, never now() -- the database is UTC and the application writes America/Chicago.
 *
 * @author Nabeel Ahmed
 */
@Component
public class JdbcCallbackReceipts implements CallbackReceipts {

    private final JdbcTemplate jdbc;

    public JdbcCallbackReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Receipt> claim(Long jobQueueId, String key, String request, LocalDateTime receivedAt) {
        int claimed = this.jdbc.update("INSERT INTO worker_callback_receipt (job_queue_id, idempotency_key, request, received_at) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (job_queue_id, idempotency_key) DO NOTHING",
            jobQueueId, key, request, Timestamp.valueOf(receivedAt));
        return claimed == 1 ? Optional.empty() : this.find(jobQueueId, key);
    }

    @Override
    public void record(Long jobQueueId, String key, String outcomeStatus, String outcomeMessage) {
        this.jdbc.update("UPDATE worker_callback_receipt SET outcome_status = ?, outcome_message = ? "
            + "WHERE job_queue_id = ? AND idempotency_key = ?", outcomeStatus, outcomeMessage, jobQueueId, key);
    }

    @Override
    public Optional<Receipt> find(Long jobQueueId, String key) {
        List<Receipt> found = this.jdbc.query("SELECT request, outcome_status, outcome_message FROM worker_callback_receipt "
            + "WHERE job_queue_id = ? AND idempotency_key = ?",
            (rs, i) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)), jobQueueId, key);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public int purgeReceivedBefore(LocalDateTime cutoff) {
        return this.jdbc.update("DELETE FROM worker_callback_receipt WHERE received_at < ?", Timestamp.valueOf(cutoff));
    }
}
