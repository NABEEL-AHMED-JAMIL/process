package process.engine;

import process.util.BusinessTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.model.repository.JobQueueRepository;
import process.model.service.impl.QueryService;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-63 against a real Postgres (V81): the documented incident, as the database sees it.
 *
 * A worker finished, but its token had expired and every report was refused, so its run sat in Start
 * -- counted in flight, so its job could never be dispatched again, and its outcome missing from the
 * run history and every counter. Here: the refusal is noted by the repository's own SQL, the sweep's
 * own query picks the run up, and once it is closed the job is free (the busy count the dispatcher
 * consults reads 0) and statisticsBySourceJobId agrees with the reconciled state.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class RefusedCallbackPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    private static ScratchPostgres db;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("refused_callback");
        // V164 now refuses an off-case job_queue.job_status at the INSERT. The guards here stay case-blind for rows
        // written before it (a database restored from an older dump), which is what these cases pin: the CHECK is
        // taken off in this throwaway database only, so such rows can be made at all.
        db.jdbc().execute("ALTER TABLE job_queue DROP CONSTRAINT IF EXISTS ck_job_queue_job_status_enum");
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
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9101 AND 9199");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9101 AND 9199");
    }

    @AfterEach
    void context() {
        TenantContext.clear();
    }

    private void job(long jobId, String runningStatus) {
        // Every job has a tenant (V102: its runs and schedule carry it, NOT NULL).
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') "
            + "ON CONFLICT DO NOTHING");
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority, "
            + "job_running_status) VALUES (9999, ?, ?, 'Auto', ?, 'Active', 1, ?)", jobId, BusinessTime.timestampOf(NOW.minusDays(1)),
            "refused-" + jobId, runningStatus);
    }

    private void run(long jobQueueId, long jobId, String status) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, "
            + "callback_token_hash, callback_token_attempt, callback_token_expires_at) "
            + "VALUES (?, ?, ?, ?, ?, 'Active', ?, 1, ?)", jobQueueId, jobId, status,
            BusinessTime.timestampOf(NOW.minusMinutes(40)), BusinessTime.timestampOf(NOW.minusMinutes(41)),
            "0000000000000000000000000000000000000000000000000000000000000000", BusinessTime.timestampOf(NOW.minusMinutes(1)));
    }

    /** The repository's own SQL, with JPA's positional ?n as JDBC's ?. */
    private static String nativeQuery(String method, Class<?>... parameters) throws Exception {
        return rawQuery(method, parameters).replaceAll("\\?\\d", "?");
    }

    private static String rawQuery(String method, Class<?>... parameters) throws Exception {
        return JobQueueRepository.class.getMethod(method, parameters).getAnnotation(Query.class).value();
    }

    /** Runs the note as the repository declares it: JPA binds ?n by number, JDBC by the order they appear. */
    private int note(long jobQueueId, LocalDateTime at, String reported) throws Exception {
        String sql = rawQuery("noteRefusedCallback", Long.class, Timestamp.class, String.class);
        Object[] byNumber = {jobQueueId, BusinessTime.timestampOf(at), reported};
        Matcher positions = Pattern.compile("\\?(\\d)").matcher(sql);
        List<Object> inOrder = new ArrayList<>();
        while (positions.find()) {
            inOrder.add(byNumber[Integer.parseInt(positions.group(1)) - 1]);
        }
        return this.sql.update(sql.replaceAll("\\?\\d", "?"), inOrder.toArray());
    }

    @Test
    void theRefusalIsNotedTheSweepFindsItAndTheClosedRunFreesItsJob() throws Exception {
        this.job(9101, "Start");
        this.run(191, 9101, "Start");

        int noted = this.note(191L, NOW, "Completed");
        assertThat(noted).isEqualTo(1);
        assertThat(this.sql.queryForObject(nativeQuery("getCountForInQueueJobByJobId", Long.class), Integer.class, 9101L))
            .as("noting refuses nothing more and closes nothing: the job is still busy").isEqualTo(1);

        List<Long> toClose = this.sql.queryForList(
            nativeQuery("findRunsWithRefusedCallbacks").replace("job_queue.*", "job_queue.job_queue_id"), Long.class);
        assertThat(toClose).containsExactly(191L);

        // What the sweep's saveJobQueue writes for it.
        this.sql.update("UPDATE job_queue SET job_status = 'Interrupt', end_time = ? WHERE job_queue_id = 191",
            BusinessTime.timestampOf(NOW.plusMinutes(15)));
        this.sql.update("UPDATE source_job SET job_running_status = 'Interrupt' WHERE job_id = 9101");

        assertThat(this.sql.queryForObject(nativeQuery("getCountForInQueueJobByJobId", Long.class), Integer.class, 9101L))
            .as("the job can be dispatched again").isEqualTo(0);
        assertThat(this.sql.queryForList(
            nativeQuery("findRunsWithRefusedCallbacks").replace("job_queue.*", "job_queue.job_queue_id"), Long.class))
            .as("a closed run is not swept twice").isEmpty();

        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@example.com");
        Map<String, Object> counters = this.sql.queryForMap(new QueryService().statisticsBySourceJobId(9101L));
        assertThat(((Number) counters.get("interrupt")).intValue()).isEqualTo(1);
        assertThat(((Number) counters.get("start")).intValue()).isEqualTo(0);
        assertThat(((Number) counters.get("total")).intValue()).isEqualTo(1);
    }

    /** A late report on a run that already ended marks nothing: the sweep has nothing to close. */
    @Test
    void aRefusalOnAFinishedRunIsNotNoted() throws Exception {
        this.job(9102, "Completed");
        this.run(192, 9102, "Completed");

        int noted = this.note(192L, NOW, "Completed");

        assertThat(noted).isZero();
        assertThat(this.sql.queryForObject("SELECT refused_callback_at FROM job_queue WHERE job_queue_id = 192",
            Timestamp.class)).isNull();
    }

    /** Case-blind, as every in-flight test here is: a status written in another case is still in flight. */
    @Test
    void theInFlightGuardIsCaseBlind() throws Exception {
        this.job(9103, "Running");
        this.run(193, 9103, "RUNNING");

        int noted = this.note(193L, NOW, "log");

        assertThat(noted).isEqualTo(1);
    }
}
