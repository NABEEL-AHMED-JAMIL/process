package process.ai;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core's model-choice rows against a real etl_job (V182): the schedule's setting is written in the job's own workspace
 * only and survives the job being saved from its form (JPA maps it read-only); a run's "Run with..." is written with the
 * run; each AI step of an attempt is one row, re-prepared in place; and a result made from an AI step names the model,
 * connection, option, choice and prompt version that step ran on.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres).
 */
class ModelChoiceStorePostgresTest {

    private static final long A = 7821L;
    private static final long B = 7822L;
    private static final long A_JOB = 78210L;
    private static final long B_JOB = 78220L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static JdbcTemplate sql;
    private static JdbcModelChoiceStore store;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("model_choice_store");
        jpa = new ScratchJpa(db);
        sql = db.jdbc();
        store = new JdbcModelChoiceStore(sql);
        for (long[] j : new long[][] {{A_JOB, A}, {B_JOB, B}}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, complete_job, "
                + "fail_job, skip_job) VALUES (?, ?, 'Manual', 'claims', 'Active', 1, ?, false, false, false)",
                j[0], Timestamp.valueOf(LocalDateTime.now().minusDays(1)), j[1]);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private static long run(long jobId, long id) {
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status, attempt) "
            + "VALUES (?, ?, 'Completed', now(), 'Active', 1)", id, jobId);
        return id;
    }

    private static RunAiStep step(long runId, int attempt, String key, String model, Long optionId, String choice, Integer version) {
        RunAiStep s = new RunAiStep();
        s.jobQueueId = runId;
        s.attempt = attempt;
        s.stepKey = key;
        s.runIn = RunAiStep.SERVER;
        s.promptId = 1049L;
        s.promptVersion = version;
        s.modelProfile = optionId == null ? null : String.valueOf(optionId);
        s.profileSource = optionId == null ? null : ModelProfiles.FROM_SCHEDULE;
        s.outcome = RunAiStep.ANSWERED;
        s.model = model;
        s.connectionId = 1003L;
        s.modelOptionId = optionId;
        s.modelChoice = choice;
        return s;
    }

    @Test
    void theSchedulesSettingIsWrittenInTheJobsOwnWorkspaceOnly() {
        assertThat(store.saveScheduleProfiles(A_JOB, A, "{\"summary\":\"1204\"}", 7601L)).isEqualTo(1);
        assertThat(store.scheduleProfiles(A_JOB, A)).isEqualTo("{\"summary\":\"1204\"}");

        store.saveScheduleProfiles(B_JOB, B, "{\"summary\":\"2204\"}", null);
        assertThat(store.saveScheduleProfiles(B_JOB, A, "{\"summary\":\"1204\"}", 7601L)).as("A naming B's job").isZero();
        assertThat(store.scheduleProfiles(B_JOB, B)).isEqualTo("{\"summary\":\"2204\"}");
        assertThat(store.scheduleProfiles(B_JOB, A)).as("B's setting read as A").isNull();
    }

    /** Saving a job from its form never puts back a setting somebody has since changed: JPA maps it read-only. */
    @Test
    void aJobSavedFromItsFormKeepsTheSchedulesModel() {
        store.saveScheduleProfiles(A_JOB, A, "{\"summary\":\"1204\"}", null);
        SourceJobRepository jobs = jpa.repository(SourceJobRepository.class);
        SourceJob loaded = jpa.transactions().execute(status -> jobs.findById(A_JOB).get());
        assertThat(loaded.getModelProfiles()).isEqualTo("{\"summary\":\"1204\"}");

        store.saveScheduleProfiles(A_JOB, A, "{\"summary\":\"1300\"}", null);
        loaded.setJobName("claims renamed");
        loaded.setModelProfiles(null);
        jpa.transactions().execute(status -> jobs.save(loaded));

        assertThat(sql.queryForObject("SELECT job_name FROM source_job WHERE job_id = ?", String.class, A_JOB)).isEqualTo("claims renamed");
        assertThat(store.scheduleProfiles(A_JOB, A)).isEqualTo("{\"summary\":\"1300\"}");
    }

    @Test
    void aRunsRunWithIsWrittenWithTheRun() {
        JobQueueRepository runs = jpa.repository(JobQueueRepository.class);
        JobQueue run = new JobQueue();
        run.setJobId(A_JOB);
        run.setTenantId(A);
        run.setJobStatus(JobStatus.Completed);
        run.setStatus(Status.Active);
        run.setModelProfiles("{\"summary\":\"1300\"}");
        JobQueue saved = jpa.transactions().execute(status -> runs.save(run));

        assertThat(sql.queryForObject("SELECT model_profiles FROM job_queue WHERE job_queue_id = ?", String.class, saved.getJobQueueId()))
            .isEqualTo("{\"summary\":\"1300\"}");
        String read = jpa.transactions().execute(status -> runs.findById(saved.getJobQueueId()).get().getModelProfiles());
        assertThat(read).isEqualTo("{\"summary\":\"1300\"}");
    }

    @Test
    void eachStepOfAnAttemptIsOneRowRePreparedInPlace() {
        long runId = run(A_JOB, 782101);
        store.recordSteps(Arrays.asList(step(runId, 1, "summary", "llama3.1:8b", 1204L, "override", 3),
            step(runId, 1, "caption", null, null, null, 4)));
        RunAiStep refused = step(runId, 1, "summary", null, 1204L, null, null);
        refused.outcome = RunAiStep.REFUSED;
        refused.error = "That model is not one this step may run on.";
        store.recordSteps(Collections.singletonList(refused));
        store.recordSteps(Collections.singletonList(step(runId, 2, "summary", "gpt-4o-mini", 1300L, "override", 3)));

        List<RunAiStep> steps = store.stepsOfRun(runId);

        assertThat(steps).extracting(s -> s.attempt + ":" + s.stepKey).containsExactly("1:summary", "1:caption", "2:summary");
        assertThat(steps.get(0).outcome).isEqualTo(RunAiStep.REFUSED);
        assertThat(steps.get(0).error).startsWith("That model is not one");
        assertThat(steps.get(0).model).isNull();
        assertThat(steps.get(2).model).isEqualTo("gpt-4o-mini");
        assertThat(steps.get(2).modelOptionId).isEqualTo(1300L);
        assertThat(steps.get(2).profileSource).isEqualTo("schedule");
        assertThat(steps.get(2).dateCreated).isNotNull();
        assertThat(sql.queryForObject("SELECT tenant_id FROM run_ai_step WHERE job_queue_id = ? LIMIT 1", Long.class, runId))
            .as("the run's workspace").isEqualTo(A);
    }

    @Test
    void aResultNamesTheModelAndPromptVersionItsStepRanOn() {
        long runId = run(A_JOB, 782201);
        store.recordSteps(Arrays.asList(step(runId, 1, "summary", "llama3.1:8b", 1204L, "override", 3),
            step(runId, 2, "summary", "gpt-4o-mini", 1300L, "default", 4)));
        ResultRecords results = new ResultRecords(sql);

        long made = results.write(runId, "{\"case_id\":\"WC-88213\"}", "hmac:3f2a", 1001L, "summary");
        long plain = results.write(runId, "{\"case_id\":\"WC-88214\"}", null, null, null);

        Map<String, Object> row = sql.queryForMap("SELECT * FROM result_record WHERE result_record_id = ?", made);
        assertThat(row).containsEntry("tenant_id", A).containsEntry("step_key", "summary").containsEntry("model", "gpt-4o-mini")
            .containsEntry("model_option_id", 1300L).containsEntry("model_choice", "default").containsEntry("model_connection_id", 1003L)
            .containsEntry("prompt_id", 1049L).containsEntry("prompt_version", 4).containsEntry("review_status", "PENDING");
        Map<String, Object> none = sql.queryForMap("SELECT * FROM result_record WHERE result_record_id = ?", plain);
        assertThat(none.get("model")).isNull();
        assertThat(none.get("prompt_version")).isNull();
        assertThat(none.get("step_key")).isNull();
    }

    /**
     * As the application reaches the database since V181 (process_app, MIG-258): run_ai_step is guarded like every tenant
     * table. A's session sees A's steps only, cannot file a step under B's run, and cannot set B's schedule.
     */
    @Test
    void asTheApplicationEachWorkspaceSeesAndWritesItsOwnStepsOnly() {
        long runA = run(A_JOB, 782301);
        long runB = run(B_JOB, 782302);
        store.recordSteps(Arrays.asList(step(runA, 1, "summary", "llama3.1:8b", 1204L, "override", 3),
            step(runB, 1, "summary", "bravo-secret-model", 2204L, "override", 3)));
        JdbcModelChoiceStore app = new JdbcModelChoiceStore(db.appJdbc());

        assertThat(RowSecurity.forTenant(A, () -> app.stepsOfRun(runA))).hasSize(1);
        assertThat(RowSecurity.forTenant(A, () -> app.stepsOfRun(runB))).as("B's steps, read as A").isEmpty();
        assertThatThrownBy(() -> RowSecurity.forTenant(A, () -> {
            app.recordSteps(Collections.singletonList(step(runB, 2, "summary", "acme-model", 1204L, "override", 3)));
            return null;
        })).as("a step filed under B's run by A").isInstanceOf(DataAccessException.class);
        assertThat(RowSecurity.forTenant(A, () -> app.saveScheduleProfiles(B_JOB, B, "{\"summary\":\"1204\"}", null)))
            .as("B's schedule, set as A").isZero();
        RowSecurity.forTenant(A, () -> {
            app.recordSteps(Collections.singletonList(step(runA, 2, "summary", "gpt-4o-mini", 1300L, "override", 3)));
            return null;
        });
        assertThat(sql.queryForObject("SELECT count(*) FROM run_ai_step WHERE job_queue_id = ?", Long.class, runB)).isEqualTo(1L);
        assertThat(sql.queryForObject("SELECT count(*) FROM run_ai_step WHERE job_queue_id = ?", Long.class, runA)).isEqualTo(2L);
    }

    @Test
    void runAiStepCarriesTheSamePolicyAsEveryTenantTable() {
        assertThat(sql.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'public.run_ai_step'::regclass",
            Boolean.class)).isTrue();
        assertThat(sql.queryForList("SELECT policyname FROM pg_policies WHERE schemaname = 'public' AND tablename = 'run_ai_step'",
            String.class)).containsExactly("tenant_isolation");
        assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_ai_step'", String.class))
            .isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'step_execution'", String.class));
        assertThat(sql.queryForObject("SELECT with_check FROM pg_policies WHERE tablename = 'run_ai_step'", String.class))
            .isEqualTo(sql.queryForObject("SELECT with_check FROM pg_policies WHERE tablename = 'step_execution'", String.class));
    }
}
