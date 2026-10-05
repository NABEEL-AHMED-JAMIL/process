package process.outbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event audit E8 against a real Postgres, as the application runs it (process_app under row security, nobody signed in):
 * dispatch_outbox rows published or abandoned more than seven days ago are deleted, in batches, whichever workspace they
 * belong to; a newer one, and a pending one however old, stay.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class DispatchOutboxPurgePostgresTest {

    private static ScratchPostgres db;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("dispatch_purge");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void empty() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM dispatch_outbox");
    }

    private void row(long run, long tenant, String published, String abandoned) {
        this.sql.update("INSERT INTO dispatch_outbox (job_queue_id, attempt, tenant_id, topic, message_key, headers, created_at, published_at, "
            + "abandoned_at) VALUES (?, 1, ?, 't', ?, '{}', now() - interval '30 days', now() - ?::interval, now() - ?::interval)", run,
            tenant, "run:" + run, published, abandoned);
    }

    private long left(long run) {
        return this.sql.queryForObject("SELECT count(*) FROM dispatch_outbox WHERE job_queue_id = ?", Long.class, run);
    }

    @Test
    void handOffsDoneMoreThanAWeekAgoGoInEveryWorkspaceAndNothingElse() {
        row(1, 9701, "8 days", null);
        row(2, 9702, null, "9 days");
        row(3, 9701, "6 days", null);
        row(4, 9702, null, null);
        this.sql.update("UPDATE dispatch_outbox SET created_at = now() - interval '60 days' WHERE job_queue_id = 4");

        assertThat(new DispatchOutboxPurge(db.appJdbc()).purge()).isEqualTo(2);

        assertThat(left(1)).as("published 8 days ago").isZero();
        assertThat(left(2)).as("abandoned 9 days ago, another workspace").isZero();
        assertThat(left(3)).as("published 6 days ago").isEqualTo(1);
        assertThat(left(4)).as("pending, however old").isEqualTo(1);
    }

    @Test
    void aBacklogLargerThanABatchGoesInBatches() {
        this.sql.update("INSERT INTO dispatch_outbox (job_queue_id, attempt, tenant_id, topic, message_key, headers, published_at) "
            + "SELECT g, 1, 9703, 't', 'run:' || g, '{}', now() - interval '10 days' FROM generate_series(1000, 1000 + ?) g",
            DispatchOutboxPurge.BATCH + 10);

        assertThat(new DispatchOutboxPurge(db.appJdbc()).purge()).isEqualTo(DispatchOutboxPurge.BATCH + 11);
        assertThat(this.sql.queryForObject("SELECT count(*) FROM dispatch_outbox", Long.class)).isZero();
    }
}
