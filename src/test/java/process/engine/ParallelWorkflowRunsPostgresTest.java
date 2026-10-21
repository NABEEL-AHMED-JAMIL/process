package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import process.inbox.JdbcInboxTriggerStore;
import process.model.service.impl.RunFacets;
import process.model.repository.JobQueueRepository;
import process.ScratchPostgres;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V220 (owner 2026-10-21) against a real Postgres: a job's runs that workflow requests started run side by side -- one in
 * flight per job and request -- and never count as the job being busy, while the job's own runs (Run now, the schedule,
 * the inbox) keep one in flight among themselves, as V83 made them.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class ParallelWorkflowRunsPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);
    private static final long JOB = 9301L;

    private static ScratchPostgres db;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("parallel_workflow_runs");
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
            + "VALUES (9999, ?, ?, 'Manual', 'episode pipeline', 'Active', 1)", JOB, Timestamp.valueOf(NOW.minusDays(1)));
    }

    /** One run of the job: the job's own (instance null) or a workflow request's. */
    private void run(long jobQueueId, String status, Long instance) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, workflow_instance_id, "
            + "workflow_step_key) VALUES (?, ?, ?, ?, ?, 'Active', ?, ?)", jobQueueId, JOB, status, Timestamp.valueOf(NOW),
            Timestamp.valueOf(NOW), instance, instance == null ? null : "check");
    }

    /** The job's busy count, read by the query the scheduler and Run now's callers use, as it is written. */
    private int busy() throws Exception {
        String count = JobQueueRepository.class.getMethod("getCountForInQueueJobByJobId", Long.class)
            .getAnnotation(Query.class).value().replace("?1", "?");
        return this.sql.queryForObject(count, Integer.class, JOB);
    }

    @Test
    void workflowRequestsRunAJobSideBySideBesideItsOwnRun() throws Exception {
        this.run(930101L, "Running", null);
        this.run(930102L, "Running", 1001L);
        this.run(930103L, "Queue", 1002L);
        this.run(930104L, "Start", 1003L);
        assertThat(this.sql.queryForObject("SELECT count(*) FROM job_queue WHERE job_id = ? AND UPPER(job_status) IN ('QUEUE','START','RUNNING')",
            Integer.class, JOB)).isEqualTo(4);
        assertThat(this.busy()).as("only the job's own run makes it busy").isEqualTo(1);
    }

    @Test
    void aRequestHasOneRunOfAJobInFlightAndAsksTwiceForNothing() throws Exception {
        this.run(930201L, "Running", 1001L);
        assertThatThrownBy(() -> this.run(930202L, "Queue", 1001L))
            .satisfies(e -> assertThat(OneRunInFlight.isWorkflowViolation(e)).isTrue())
            .satisfies(e -> assertThat(OneRunInFlight.isViolation(e)).isFalse());
        // Ended, its next run may start.
        this.sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id = 930201");
        this.run(930203L, "Queue", 1001L);
        assertThat(this.inFlightOf(1001L)).as("what a request asking twice is answered with").isEqualTo(930203L);
    }

    private Long inFlightOf(long instance) throws Exception {
        String find = JobQueueRepository.class.getMethod("findInFlightWorkflowRun", Long.class, Long.class, Long.class)
            .getAnnotation(Query.class).value().replace("?1", "?").replace("?2", "?").replace("?3", "?");
        return this.sql.queryForObject(find, Long.class, JOB, instance, 9999L);
    }

    @Test
    void theJobsOwnRunsKeepOneInFlightAmongThemselvesWithWorkflowRunsGoing() throws Exception {
        this.run(930301L, "Running", 1001L);
        this.run(930302L, "Queue", null);
        assertThatThrownBy(() -> this.run(930303L, "Queue", null))
            .satisfies(e -> assertThat(OneRunInFlight.isViolation(e)).isTrue());
        assertThat(this.busy()).isEqualTo(1);
        assertThat(new JdbcInboxTriggerStore(this.sql).inFlight(JOB)).as("the inbox waits for the job's own run only").isTrue();
        this.sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id = 930302");
        assertThat(this.busy()).as("workflow runs alone leave the job free").isZero();
        assertThat(new JdbcInboxTriggerStore(this.sql).inFlight(JOB)).isFalse();
    }

    @Test
    void aWorkflowRunIsAlwaysInTheJobsOwnLane() {
        assertThatThrownBy(() -> this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, "
            + "run_lane, backfill_id, workflow_instance_id) VALUES (930401, ?, 'Queue', ?, ?, 'Active', 1, 1, 1001)", JOB,
            Timestamp.valueOf(NOW), Timestamp.valueOf(NOW))).hasMessageContaining("ck_job_queue_workflow_lane");
    }

    @Test
    void aReviewsRerunIsNeverAWorkflowsRun() throws Exception {
        this.run(930501L, "Failed", null);
        this.run(930502L, "Running", 1001L);
        String newest = JobQueueRepository.class.getMethod("findNewestRunIdAfter", Long.class, long.class)
            .getAnnotation(Query.class).value().replace(":jobId", "?").replace(":afterId", "?");
        assertThat(this.sql.queryForObject(newest, Long.class, JOB, 930501L)).isNull();
        this.run(930503L, "Queue", null);
        assertThat(this.sql.queryForObject(newest, Long.class, JOB, 930501L)).isEqualTo(930503L);
    }

    @Test
    void aWorkflowsRunReadsWorkflowAsItsTriggerNotRunNow() {
        this.run(930601L, "Running", 1001L);
        this.run(930602L, "Queue", null);
        this.sql.update("UPDATE job_queue SET run_manual = true WHERE job_id = ?", JOB);
        String trigger = "SELECT " + RunFacets.triggerSql("q") + " FROM job_queue q WHERE q.job_queue_id = ?";
        assertThat(this.sql.queryForObject(trigger, String.class, 930601L)).isEqualTo("workflow");
        assertThat(this.sql.queryForObject(trigger, String.class, 930602L)).isEqualTo("manual");
    }
}
