package process.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What Core writes to api_event_out itself (MIG-333): a review decision, in the transaction that records it. A run's status,
 * a made file and a settled form submission are written by V203's triggers, with the row that changed.
 */
@Component
public class CustomerEventJournal {

    private final JdbcTemplate jdbc;

    public CustomerEventJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A party decided on this run's review: run.review.decided, the run and its review as they stand once relayed. */
    public void reviewDecided(long tenantId, long jobQueueId) {
        this.jdbc.update("INSERT INTO api_event_out (tenant_id, kind, job_queue_id) VALUES (?, 'review_decided', ?)", tenantId, jobQueueId);
    }
}
