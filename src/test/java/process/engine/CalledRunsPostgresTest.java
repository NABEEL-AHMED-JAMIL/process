package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.inbox.JdbcInboxTriggerStore;
import process.model.repository.JobQueueRepository;
import process.model.service.impl.RunFacets;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V235 (MIG-424) against a real Postgres: a run another run's Run pipeline step called goes beside the job's own runs -- one
 * in flight per job and calling run, never counted as the job being busy -- in lane 0, at most three deep.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class CalledRunsPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final long JOB = 9351L;

    private static ScratchPostgres db;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("called_runs");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void rows() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM job_queue WHERE job_id = ?", JOB);
        this.sql.update("DELETE FROM source_job WHERE job_id = ?", JOB);
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') "
            + "ON CONFLICT DO NOTHING");
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9999, ?, ?, 'Manual', 'care plan', 'Active', 1)", JOB, Timestamp.valueOf(NOW.minusDays(1)));
    }

    /** One run of the job: its own (caller null) or one a calling run's step started. */
    private void run(long jobQueueId, String status, Long caller, Integer depth) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, called_by_run_id, "
            + "call_depth) VALUES (?, ?, ?, ?, ?, 'Active', ?, ?)", jobQueueId, JOB, status, Timestamp.valueOf(NOW), Timestamp.valueOf(NOW),
            caller, depth);
    }

    private int busy() throws Exception {
        String count = JobQueueRepository.class.getMethod("getCountForInQueueJobByJobId", Long.class)
            .getAnnotation(Query.class).value().replace("?1", "?");
        return this.sql.queryForObject(count, Integer.class, JOB);
    }

    @Test
    void calledRunsGoBesideTheJobsOwnRunAndNeverMakeItBusy() throws Exception {
        this.run(935101L, "Running", null, null);
        this.run(935102L, "Running", 881L, 1);
        this.run(935103L, "Queue", 882L, 2);
        assertThat(this.busy()).as("only the job's own run makes it busy").isEqualTo(1);
        this.sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id = 935101");
        assertThat(this.busy()).isZero();
        assertThat(new JdbcInboxTriggerStore(this.sql).inFlight(JOB)).as("the inbox waits for the job's own run only").isFalse();
    }

    @Test
    void aCallingRunHasOneRunOfAJobInFlight() {
        this.run(935201L, "Running", 881L, 1);
        assertThatThrownBy(() -> this.run(935202L, "Queue", 881L, 1))
            .satisfies(e -> assertThat(OneRunInFlight.isCallerViolation(e)).isTrue())
            .satisfies(e -> assertThat(OneRunInFlight.isViolation(e)).isFalse());
        this.sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id = 935201");
        this.run(935203L, "Queue", 881L, 1);
    }

    @Test
    void aCalledRunIsInLaneZeroAndAtMostThreeDeep() {
        assertThatThrownBy(() -> this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, "
            + "run_lane, backfill_id, called_by_run_id, call_depth) VALUES (935301, ?, 'Queue', ?, ?, 'Active', 1, 1, 881, 1)", JOB,
            Timestamp.valueOf(NOW), Timestamp.valueOf(NOW))).hasMessageContaining("ck_job_queue_called_lane");
        assertThatThrownBy(() -> this.run(935302L, "Queue", 881L, 4)).hasMessageContaining("ck_job_queue_call_depth");
    }

    @Test
    void aCalledRunReadsCalledAsItsTriggerAndIsNeverAReviewsRerun() throws Exception {
        this.run(935401L, "Failed", null, null);
        this.run(935402L, "Running", 881L, 1);
        String trigger = "SELECT " + RunFacets.triggerSql("q") + " FROM job_queue q WHERE q.job_queue_id = ?";
        assertThat(this.sql.queryForObject(trigger, String.class, 935402L)).isEqualTo("called");
        String newest = JobQueueRepository.class.getMethod("findNewestRunIdAfter", Long.class, long.class)
            .getAnnotation(Query.class).value().replace(":jobId", "?").replace(":afterId", "?");
        assertThat(this.sql.queryForObject(newest, Long.class, JOB, 935401L)).isNull();
    }
}
