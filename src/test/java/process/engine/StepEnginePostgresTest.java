package process.engine;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import process.AcrossTenantsProxy;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.ai.AiPort;
import process.ai.AiStepService;
import process.ai.JdbcModelChoiceStore;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.NotifyService;
import process.model.service.impl.NotifyServiceImpl;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.pipeline.DatasetSweep;
import process.pipeline.DefinitionProblem;
import process.pipeline.DefinitionValidator;
import process.pipeline.Definitions;
import process.pipeline.FileDatasetStore;
import process.pipeline.JdbcStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepContext;
import process.pipeline.StepEngine;
import process.pipeline.StepResult;
import process.pipeline.StepTask;
import process.pipeline.StepTasks;
import process.util.OpenSearchAuditLogClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-230 end to end inside Core, against a real etl_job as the application runs it -- process_app under row-level
 * security, nobody signed in: a queued run of a pipeline with a stored step definition is taken by the pre-dispatch
 * pass, run by the step engine as its workspace, and reported through the real NotifyServiceImpl; a run of a pipeline
 * without one, in the same pass, is prepared for its worker exactly as before (the legacy wrap). A failed step's run
 * is retried by the job's own policy (BulkAction.scheduleRetry) as attempt 2 of the same row, on the same definition.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres). The run through Kafka and a real worker
 * is StepPipelineLiveIT's (opt-in, against the running stack).
 */
class StepEnginePostgresTest {

    private static final long TENANT = 7851L;
    private static final long OTHER = 7852L;
    private static final long TYPE = 78510L;
    private static final long STEPS_PIPELINE = 78511L;
    private static final long LEGACY_PIPELINE = 78512L;
    private static final long STEPS_TASK = 78513L;
    private static final long LEGACY_TASK = 78514L;
    private static final long STEPS_JOB = 78515L;
    private static final long LEGACY_JOB = 78516L;
    private static final long FAILING_JOB = 78517L;
    private static final long FAILING_TASK = 78518L;
    private static final long FAILING_PIPELINE = 78519L;
    private static final String PAYLOAD = "<pipeline><claim_id>C-17</claim_id><amount>250</amount></pipeline>";

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static JdbcTemplate login;
    private static Path datasetDir;

    private ThreadPoolExecutor runThreads;
    private PreDispatchPhase phase;
    private static long nextRun = 785100;

    /** Fails every try: what a step that cannot reach its API does. */
    static final class Refuses implements StepTask {
        @Override public String code() { return "refuses"; }
        @Override public String description() { return "always fails"; }
        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
        @Override public StepResult run(StepContext context) {
            context.log("calling the claims API");
            throw new IllegalStateException("the claims API answered 503");
        }
    }

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("step_engine");
        login = db.jdbc();
        jpa = new ScratchJpa(db.appPool());
        datasetDir = Files.createTempDirectory("etl-datasets-test");
        for (long tenant : new long[] {TENANT, OTHER}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
                "T" + tenant);
        }
        login.update("INSERT INTO source_task_type (source_task_type_id, tenant_id, service_name, description, queue_topic_partition, "
            + "task_type_status) VALUES (?, ?, 'claims', 'claims', 'topic=etl.claims&partitions=[*]', 'Active')", TYPE, TENANT);
        pipeline(STEPS_PIPELINE, "STEPS-PIPE");
        pipeline(LEGACY_PIPELINE, "LEGACY-PIPE");
        pipeline(FAILING_PIPELINE, "FAILING-PIPE");
        task(STEPS_TASK, "STEPS-PIPE");
        task(LEGACY_TASK, "LEGACY-PIPE");
        task(FAILING_TASK, "FAILING-PIPE");
        job(STEPS_JOB, STEPS_TASK, 1);
        job(LEGACY_JOB, LEGACY_TASK, 1);
        job(FAILING_JOB, FAILING_TASK, 2);
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 1, ?::json)", STEPS_PIPELINE,
            "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"shape\",\"task\":\"select\",\"config\":{\"columns\":"
                + "{\"claim_id\":\"claim\",\"amount\":\"amount\"}}},{\"key\":\"more\",\"task\":\"sample\",\"config\":{\"rows\":"
                + "[{\"claim\":\"C-18\"},{\"claim\":\"C-19\"}]}}]}");
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 1, ?::json)", FAILING_PIPELINE,
            "{\"version\":1,\"steps\":[{\"key\":\"call\",\"task\":\"refuses\",\"retry\":{\"maxAttempts\":2}},"
                + "{\"key\":\"after\",\"task\":\"sample\",\"config\":{\"rows\":[{\"a\":1}]}}]}");
    }

    private static void pipeline(long key, String id) {
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status, source_task_type_id) "
            + "VALUES (?, ?, ?, ?, 'Active', ?)", key, TENANT, id, id, TYPE);
    }

    private static void task(long id, String pipelineId) {
        login.update("INSERT INTO source_task (task_detail_id, tenant_id, source_task_type_id, task_name, task_payload, task_status, "
            + "pipeline_id, date_created) VALUES (?, ?, ?, ?, ?, 'Active', ?, now())", id, TENANT, TYPE, "task " + id, PAYLOAD, pipelineId);
    }

    private static void job(long id, long task, int maxAttempts) {
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, complete_job, fail_job, skip_job, max_attempts, retry_backoff_seconds) "
            + "VALUES (?, now(), 'Manual', ?, 'Active', 'Queue', 1, ?, ?, false, false, false, ?, 1)", id, "job " + id, TENANT, task,
            maxAttempts);
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

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T bean) {
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(jpa.transactionManager(), new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @BeforeEach
    void wire() {
        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(store, mock(NotificationPort.class));
        NotifyService notify = transactional(new NotifyServiceImpl(bulkAction, mock(JobMail.class), store, mock(NotificationPort.class)));
        StepTasks tasks = Definitions.builtInTasks(new Refuses());
        this.runThreads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        StepEngine engine = new StepEngine(new PipelineDefinitionStore(db.appJdbc()), new JdbcStepStore(db.appJdbc()), tasks,
            new DefinitionValidator(tasks), new FileDatasetStore(datasetDir.toString()), notify, store,
            new TransactionTemplate(jpa.transactionManager()), this.runThreads,
            Executors.newCachedThreadPool(), duration -> { });
        PreDispatchPhase bare = new PreDispatchPhase(store, bulkAction, new AiStepService(jpa.repository(PipelineRepository.class),
            mock(AiPort.class)), new JdbcModelChoiceStore(db.appJdbc()), mock(JobMail.class),
            new TransactionTemplate(jpa.transactionManager()), new DispatchPipeline.SameThread());
        bare.useStepEngine(engine);
        this.phase = AcrossTenantsProxy.of(bare);
    }

    private long queue(long jobId) {
        long id = ++nextRun;
        login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, status, job_send, attempt) "
            + "VALUES (?, ?, 'Queue', now(), now(), 'Active', false, 1)", id, jobId);
        return id;
    }

    /** One pre-dispatch pass, and every engine run it started, to the end. */
    private void pass() throws Exception {
        this.phase.runPass();
        long deadline = System.currentTimeMillis() + 30000;
        while (this.runThreads.getCompletedTaskCount() < this.runThreads.getTaskCount() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(this.runThreads.getCompletedTaskCount()).as("the engine finished its runs").isEqualTo(this.runThreads.getTaskCount());
    }

    private Map<String, Object> run(long id) {
        return login.queryForMap("SELECT job_status, job_send, attempt, end_reason, job_status_message, end_time, dispatch_payload, "
            + "prepared_at FROM job_queue WHERE job_queue_id = ?", id);
    }

    private List<Map<String, Object>> steps(long run) {
        return login.queryForList("SELECT attempt, step_key, status, tries, records_in, records_out, tenant_id, pipeline_definition_id, "
            + "started_at IS NOT NULL AS started, ended_at IS NOT NULL AS ended FROM step_execution WHERE job_queue_id = ? "
            + "ORDER BY attempt, step_index", run);
    }

    @Test
    void aStepPipelineRunsInTheEngineAndALegacyOneIsPreparedForItsWorkerInTheSamePass() throws Exception {
        long steps = this.queue(STEPS_JOB);
        long legacy = this.queue(LEGACY_JOB);

        this.pass();

        // The legacy wrap: prepared for the dispatcher with the task's own payload, as before; no step rows.
        Map<String, Object> legacyRun = this.run(legacy);
        assertThat(legacyRun.get("job_status")).isEqualTo("Queue");
        assertThat(legacyRun.get("job_send")).isEqualTo(false);
        assertThat(legacyRun.get("prepared_at")).isNotNull();
        assertThat(legacyRun.get("dispatch_payload")).isEqualTo(PAYLOAD);
        assertThat(this.steps(legacy)).isEmpty();

        // The step pipeline: Completed through the worker callback's own path, its steps recorded in its workspace.
        Map<String, Object> stepRun = this.run(steps);
        assertThat(stepRun.get("job_status")).isEqualTo("Completed");
        assertThat(stepRun.get("job_send")).as("handed to the engine, as a dispatched run is to its worker").isEqualTo(true);
        assertThat(login.queryForObject("SELECT length(callback_token_hash) FROM job_queue WHERE job_queue_id = ?", Integer.class, steps))
            .as("sealed: no worker token, and the shared legacy secret no longer opens it").isEqualTo(64);
        assertThat(stepRun.get("end_reason")).isEqualTo("WORKER");
        assertThat(stepRun.get("end_time")).isNotNull();
        assertThat(stepRun.get("job_status_message")).isEqualTo("2 of 2 step(s) completed.");
        assertThat(login.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = ?", String.class, STEPS_JOB))
            .isEqualTo("Completed");
        List<Map<String, Object>> rows = this.steps(steps);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("step_key", "shape").containsEntry("status", "Completed").containsEntry("tries", 1)
            .containsEntry("records_in", 1L).containsEntry("records_out", 1L).containsEntry("tenant_id", TENANT)
            .containsEntry("started", true).containsEntry("ended", true);
        assertThat(rows.get(1)).containsEntry("step_key", "more").containsEntry("records_in", 1L).containsEntry("records_out", 2L);
        assertThat(login.queryForList("SELECT d.name || ':' || d.row_count || ':' || d.storage_key || ':' || d.tenant_id FROM run_dataset d "
            + "JOIN step_execution s ON s.step_execution_id = d.step_execution_id WHERE s.job_queue_id = ? ORDER BY s.step_index",
            String.class, steps)).containsExactly("output:1:datasets/" + steps + "/1/shape/output.json:" + TENANT,
            "output:2:datasets/" + steps + "/1/more/output.json:" + TENANT);
        assertThat(Files.exists(datasetDir.resolve("datasets/" + steps + "/1/shape/output.json"))).isTrue();
        assertThat(login.queryForList("SELECT l.message FROM step_log l JOIN step_execution s ON s.step_execution_id = l.step_execution_id "
            + "WHERE s.job_queue_id = ? ORDER BY s.step_index, l.line_no", String.class, steps))
            .containsExactly("1 row(s), 2 column(s) kept.", "2 sample row(s).");
        List<String> audit = login.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ? ORDER BY job_audit_log_id",
            String.class, steps);
        assertThat(audit).contains("Taken by the step engine: pipeline STEPS-PIPE, definition version 1, 2 step(s).",
            "Running 2 step(s): shape, more.", "2 of 2 step(s) completed.");
        assertThat(audit).anyMatch(line -> line.startsWith("Step 1/2 <shape> (select) completed: 1 in, 1 out"));
    }

    @Test
    void aFailedStepFailsItsRunWhichTheJobsOwnPolicyRetriesOnTheSameDefinition() throws Exception {
        long run = this.queue(FAILING_JOB);

        this.pass();

        Map<String, Object> first = this.run(run);
        assertThat(first.get("job_status")).as("attempt 1 of 2 failed: queued again, not Failed").isEqualTo("Queue");
        assertThat(first.get("attempt")).isEqualTo(2);
        assertThat(first.get("job_send")).isEqualTo(false);
        assertThat(first.get("prepared_at")).isNull();
        assertThat((String) first.get("job_status_message")).startsWith("Attempt 1 of 2 failed: Step 1 <call> failed: the claims API "
            + "answered 503. Retrying at");

        login.update("UPDATE job_queue SET next_attempt_at = now() - interval '1 minute' WHERE job_queue_id = ?", run);
        this.pass();

        Map<String, Object> second = this.run(run);
        assertThat(second.get("job_status")).isEqualTo("Failed");
        assertThat(second.get("end_reason")).isEqualTo("WORKER");
        assertThat(second.get("job_status_message")).isEqualTo("Step 1 <call> failed: the claims API answered 503");
        assertThat(login.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = ?", String.class, FAILING_JOB))
            .isEqualTo("Failed");
        List<Map<String, Object>> rows = this.steps(run);
        assertThat(rows).extracting(r -> r.get("attempt") + ":" + r.get("step_key") + ":" + r.get("status") + ":" + r.get("tries"))
            .containsExactly("1:call:Failed:2", "1:after:Skip:0", "2:call:Failed:2", "2:after:Skip:0");
        assertThat(rows).extracting(r -> r.get("pipeline_definition_id")).containsOnly(rows.get(0).get("pipeline_definition_id"));
        assertThat(login.queryForList("SELECT l.level || ' ' || l.message FROM step_log l JOIN step_execution s "
            + "ON s.step_execution_id = l.step_execution_id WHERE s.job_queue_id = ? AND s.attempt = 1 AND s.step_key = 'call' "
            + "ORDER BY l.line_no", String.class, run)).containsExactly("INFO Try 1 of 2.", "INFO calling the claims API",
            "WARN Try 1 of 2 failed: the claims API answered 503", "INFO Try 2 of 2.", "INFO calling the claims API",
            "ERROR Try 2 of 2 failed: the claims API answered 503");
    }

    @Test
    void anotherWorkspaceSeesNoneOfARunsSteps() throws Exception {
        long steps = this.queue(STEPS_JOB);
        this.pass();
        JdbcTemplate app = db.appJdbc();
        long mine = RowSecurity.forTenant(TENANT, () -> app.queryForObject(
            "SELECT count(*) FROM step_execution WHERE job_queue_id = ?", Long.class, steps));
        long theirs = RowSecurity.forTenant(OTHER, () -> app.queryForObject(
            "SELECT count(*) FROM step_execution WHERE job_queue_id = ?", Long.class, steps));
        assertThat(mine).isEqualTo(2);
        assertThat(theirs).isZero();
    }

    /** A dataset past its retention is removed -- the file and its row -- by the sweep, across workspaces. */
    @Test
    void anExpiredDatasetIsSweptAway() throws Exception {
        long steps = this.queue(STEPS_JOB);
        this.pass();
        String key = "datasets/" + steps + "/1/shape/output.json";
        assertThat(Files.exists(datasetDir.resolve(key))).isTrue();
        login.update("UPDATE run_dataset SET expires_at = now() - interval '1 minute' WHERE storage_key = ?", key);

        DatasetSweep sweep = AcrossTenantsProxy.of(new DatasetSweep(db.appJdbc(), new FileDatasetStore(datasetDir.toString())));
        assertThat(sweep.sweep()).isEqualTo(1);

        assertThat(Files.exists(datasetDir.resolve(key))).isFalse();
        assertThat(login.queryForObject("SELECT count(*) FROM run_dataset WHERE storage_key = ?", Long.class, key)).isZero();
        assertThat(Files.exists(datasetDir.resolve("datasets/" + steps + "/1/more/output.json"))).as("not expired").isTrue();
        assertThat(login.queryForObject("SELECT records_out FROM step_execution WHERE job_queue_id = ? AND step_key = 'shape'", Long.class,
            steps)).as("the step still says what it did").isEqualTo(1L);
    }
}
