package process.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.pipeline.backing.ReadTakes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ReadTakes} in run_read_take (V221), as the run's workspace (the step engine runs a run as its workspace; row
 * security keeps every statement to it). A take is one transaction holding a lock for its job, step and source, so two
 * runs asking at the same moment take one after the other.
 */
@Component
public class JdbcReadTakes implements ReadTakes {

    private static final Set<String> IN_FLIGHT = Set.of("QUEUE", "START", "RUNNING");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcReadTakes(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Taken take(long jobQueueId, long jobId, String stepKey, String source, List<String> items, Set<String> fresh, int limit) {
        return this.transactions.execute(status -> {
            this.jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "run_read_take|" + jobId + "|" + stepKey + "|" + source);
            // A completed run's items are behind the job's watermark: they need no row to stay read.
            this.jdbc.update("DELETE FROM run_read_take t USING job_queue q WHERE q.job_queue_id = t.job_queue_id AND t.job_id = ? "
                + "AND t.step_key = ? AND t.source = ? AND UPPER(q.job_status) = 'COMPLETED'", jobId, stepKey, source);
            // Each item taken before: whether another run in flight holds it (true), or it is free again (false).
            Map<String, Boolean> before = new HashMap<>();
            this.jdbc.query("SELECT t.item, t.job_queue_id, UPPER(q.job_status) FROM run_read_take t JOIN job_queue q "
                + "ON q.job_queue_id = t.job_queue_id WHERE t.job_id = ? AND t.step_key = ? AND t.source = ?",
                rs -> {
                    before.put(rs.getString(1), rs.getLong(2) != jobQueueId && IN_FLIGHT.contains(rs.getString(3)));
                }, jobId, stepKey, source);
            List<String> taken = new ArrayList<>();
            int held = 0;
            boolean more = false;
            for (String item : items) {
                Boolean heldByOther = before.get(item);
                if (Boolean.TRUE.equals(heldByOther)) {
                    held++;
                } else if (heldByOther != null || fresh.contains(item)) {
                    if (taken.size() >= limit) {
                        more = true;
                        break;
                    }
                    taken.add(item);
                }
            }
            List<Object[]> rows = new ArrayList<>();
            for (String item : taken) {
                rows.add(new Object[] {jobQueueId, jobId, stepKey, source, item});
            }
            this.jdbc.batchUpdate("INSERT INTO run_read_take (job_queue_id, job_id, step_key, source, item) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (job_id, step_key, source, item) DO UPDATE SET job_queue_id = EXCLUDED.job_queue_id, date_updated = now()", rows);
            return new Taken(taken, held, more);
        });
    }

    @Override
    public List<String> released(long jobId, String stepKey, String source) {
        return this.jdbc.queryForList("SELECT t.item FROM run_read_take t JOIN job_queue q ON q.job_queue_id = t.job_queue_id "
            + "WHERE t.job_id = ? AND t.step_key = ? AND t.source = ? AND UPPER(q.job_status) NOT IN ('QUEUE', 'START', 'RUNNING', 'COMPLETED') "
            + "ORDER BY t.item", String.class, jobId, stepKey, source);
    }
}
