package process.engine;

import org.barco.platform.meter.Meter;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import process.AcrossTenantsProxy;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.ai.AiPort;
import process.ai.AiStepService;
import process.ai.JdbcModelChoiceStore;
import process.model.dto.ResponseDto;
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
import process.pipeline.JdbcRunAttemptStore;
import process.pipeline.JdbcStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunRetries;
import process.pipeline.StepContext;
import process.pipeline.StepEngine;
import process.pipeline.StepResult;
import process.pipeline.StepTask;
import process.pipeline.StepTasks;
import process.pipeline.StepTimelineService;
import process.pipeline.review.JdbcRunReviewStore;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;
import process.util.OpenSearchAuditLogClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import java.util.Arrays;

/**
 * MIG-345 against a real etl_job as the application runs it (process_app under row security): a run that fails at step
 * 3 is fixed where it failed and retried from step 3 -- through the console's service as a person of its workspace -- as
 * attempt 2 of the same row; the pre-dispatch pass hands it to the step engine, which records steps 1 and 2 as reused
 * (reused_from, no times, their tasks never called) and runs 3 and 4 on the fixed version; the attempt is recorded with
 * who asked; the run is metered once (pipeline.runs keyed by the run); another workspace sees and retries nothing; the
 * sweep leaves a run in flight alone.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres).
 */
class RunRetryPostgresTest {

    private static final long TENANT = 7961L;
    private static final long OTHER = 7962L;
    private static final long ADMIN = 79611L;
    private static final long OTHER_ADMIN = 79621L;
    private static final long TYPE = 79610L;
    /** Each test's own pipeline, task and job: one run in flight per job, and its own definition versions. */
    private static long nextPipeline = 79620L;
    private long pipelineKey;
    private long jobId;
    private static final String PAYLOAD = "<pipeline><order>7</order></pipeline>";

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static JdbcTemplate login;
    private static Path datasetDir;
    private static long nextRun = 796100;

    /** Each step's task runs, by step key, over every attempt. */
    private static final Map<String, AtomicInteger> RAN = new ConcurrentHashMap<>();

    private ThreadPoolExecutor runThreads;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final List<UsageEvent> metered = new CopyOnWriteArrayList<>();
    private PreDispatchPhase phase;
    private RunRetries retries;
    private StepTimelineService timeline;

    /** Passes its input on, counting. */
    static final class Counted implements StepTask {
        @Override public String code() { return "counted"; }

        @Override public String description() { return "passes its input on"; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            RAN.computeIfAbsent(context.stepKey(), k -> new AtomicInteger()).incrementAndGet();
            return StepResult.of(context.input());
        }
    }

    /** Fails unless its config says pass: a validate step against a contract later loosened. */
    static final class Gate implements StepTask {
        @Override public String code() { return "gate"; }

        @Override public String description() { return "fails until loosened"; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            RAN.computeIfAbsent(context.stepKey(), k -> new AtomicInteger()).incrementAndGet();
            if (!Boolean.TRUE.equals(context.config().get("pass"))) {
                throw new IllegalStateException("2 row(s) break the contract");
            }
            return StepResult.of(context.input());
        }
    }

    private static String definition(boolean pass, String shapeColumn) {
        return "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":{\"rows\":[{\"id\":1,\"amount\":50},"
            + "{\"id\":2,\"amount\":120},{\"id\":3,\"amount\":400}]}},{\"key\":\"shape\",\"task\":\"select\",\"config\":{\"columns\":"
            + "{\"id\":\"" + shapeColumn + "\",\"amount\":\"amount\"}}},{\"key\":\"check\",\"task\":\"gate\",\"config\":{\"pass\":" + pass
            + "}},{\"key\":\"after\",\"task\":\"counted\"}]}";
    }

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("run_retry");
        login = db.jdbc();
        jpa = new ScratchJpa(db.appPool());
        datasetDir = Files.createTempDirectory("etl-datasets-retry");
        for (long tenant : new long[] {TENANT, OTHER}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
                "T" + tenant);
        }
        login.update("INSERT INTO source_task_type (source_task_type_id, tenant_id, service_name, description, queue_topic_partition, "
            + "task_type_status) VALUES (?, ?, 'orders', 'orders', 'topic=etl.orders&partitions=[*]', 'Active')", TYPE, TENANT);
    }

    /** A pipeline of its own for a test: version 1 fails at step 3 <check>. */
    private void ownPipeline() {
        long key = ++nextPipeline * 10;
        String id = "ORDERS-" + key;
        this.pipelineKey = key;
        this.jobId = key + 3;
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status, source_task_type_id) "
            + "VALUES (?, ?, ?, ?, 'Active', ?)", key, TENANT, id, id, TYPE);
        login.update("INSERT INTO source_task (task_detail_id, tenant_id, source_task_type_id, task_name, task_payload, task_status, "
            + "pipeline_id, date_created) VALUES (?, ?, ?, 'orders', ?, 'Active', ?, now())", key + 2, TENANT, TYPE, PAYLOAD, id);
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, complete_job, fail_job, skip_job, max_attempts, retry_backoff_seconds) "
            + "VALUES (?, now(), 'Manual', 'orders', 'Active', 'Queue', 1, ?, ?, false, false, false, 1, 1)", key + 3, TENANT, key + 2);
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 1, ?::json)", key,
            definition(false, "id"));
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

    // also drives the deprecated API the code under test still uses (QUALITY.md lists them)
    @SuppressWarnings({"unchecked", "deprecation"})
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
        NotifyServiceImpl callback = new NotifyServiceImpl(bulkAction, mock(JobMail.class), store, mock(NotificationPort.class));
        MeterReporter meter = mock(MeterReporter.class);
        doAnswer(call -> this.metered.add(call.getArgument(0))).when(meter).report(any(UsageEvent.class));
        ReflectionTestUtils.setField(callback, "meter", meter);
        NotifyService notify = transactional(callback);
        StepTasks tasks = Definitions.builtInTasks(new Counted(), new Gate());
        this.runThreads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                submitted.incrementAndGet();
                super.execute(command);
            }

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);
                finished.incrementAndGet();
            }
        };
        JdbcStepStore steps = new JdbcStepStore(db.appJdbc());
        JdbcRunAttemptStore attempts = new JdbcRunAttemptStore(db.appJdbc());
        PipelineDefinitionStore definitions = new PipelineDefinitionStore(db.appJdbc());
        StepEngine engine = new StepEngine(definitions, steps, tasks, new DefinitionValidator(tasks),
            new FileDatasetStore(datasetDir.toString()), notify, store, new TransactionTemplate(jpa.transactionManager()), this.runThreads,
            Executors.newCachedThreadPool(), duration -> { });
        engine.useAttempts(attempts);
        PreDispatchPhase bare = new PreDispatchPhase(store, bulkAction, new AiStepService(jpa.repository(PipelineRepository.class),
            mock(AiPort.class)), new JdbcModelChoiceStore(db.appJdbc()), mock(JobMail.class),
            new TransactionTemplate(jpa.transactionManager()), new DispatchPipeline.SameThread());
        bare.useStepEngine(engine);
        this.phase = AcrossTenantsProxy.of(bare);
        this.retries = new RunRetries(steps, attempts, definitions, new DefinitionValidator(tasks), tasks, bulkAction, db.appJdbc(),
            new TransactionTemplate(jpa.transactionManager()));
        this.timeline = new StepTimelineService(jpa.repository(JobQueueRepository.class), jpa.repository(SourceJobRepository.class), steps,
            new JdbcModelChoiceStore(db.appJdbc()), new FileDatasetStore(datasetDir.toString()),
            new RunReviews(steps, definitions, new JdbcRunReviewStore(db.appJdbc())));
        this.timeline.useRetries(this.retries, attempts);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private long queue() {
        long id = ++nextRun;
        login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, status, job_send, attempt) "
            + "VALUES (?, ?, 'Queue', now(), now(), 'Active', false, 1)", id, this.jobId);
        login.update("UPDATE source_job SET job_running_status = 'Queue' WHERE job_id = ?", this.jobId);
        return id;
    }

    private void pass() throws Exception {
        this.phase.runPass();
        long deadline = System.currentTimeMillis() + 30000;
        while (this.finished.get() < this.submitted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(this.finished.get()).as("the engine finished its runs").isEqualTo(this.submitted.get());
    }

    private Map<String, Object> run(long id) {
        return login.queryForMap("SELECT job_status, job_send, attempt, job_status_message, end_time, prepared_at FROM job_queue "
            + "WHERE job_queue_id = ?", id);
    }

    private static int ran(String key) {
        AtomicInteger count = RAN.get(key);
        return count == null ? 0 : count.get();
    }

    private static void asAdminOf(long tenant, long user) {
        TenantContext.set(tenant, "TENANT_ADMIN", user, "admin" + user + "@example.test");
    }

    /** A run of the job that failed at step 3 <check> on the first version. */
    private long failedAtStepThree() throws Exception {
        this.ownPipeline();
        long run = this.queue();
        this.pass();
        assertThat(this.run(run).get("job_status")).isEqualTo("Failed");
        return run;
    }

    @Test
    void aRunFixedAtTheStepThatFailedResumesThereAsItsNextAttemptAndStepsOneAndTwoAreNotRunAgain() throws Exception {
        long run = this.failedAtStepThree();
        int shapeBefore = ran("check");
        long fixed = login.queryForObject("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 2, ?::json) "
            + "RETURNING pipeline_definition_id", Long.class, this.pipelineKey, definition(true, "id"));
        Map<String, Object> firstSteps = login.queryForMap("SELECT min(ended_at) AS ended FROM step_execution WHERE job_queue_id = ? "
            + "AND attempt = 1 AND step_index < 2", run);

        asAdminOf(TENANT, ADMIN);
        ResponseDto plan = this.timeline.retryPlan(run);
        assertThat(plan.getStatus()).isEqualTo("SUCCESS");
        assertThat(plan.getData().toString()).contains("resumable=true", "fromKey=check", "key=read", "key=shape", "restartable=true");
        ResponseDto asked = this.timeline.retry(run, "failed_step");
        assertThat(asked.getStatus()).as(asked.getMessage()).isEqualTo("SUCCESS");
        assertThat(asked.getMessage()).isEqualTo("Run " + run + " queued to resume from step 3 <check> as attempt 2, reusing 2 step(s).");
        TenantContext.clear();

        Map<String, Object> queued = this.run(run);
        assertThat(queued.get("job_status")).isEqualTo("Queue");
        assertThat(queued.get("attempt")).isEqualTo(2);
        assertThat(queued.get("job_send")).isEqualTo(false);
        assertThat(queued.get("end_time")).isNull();
        assertThat(login.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = ?", String.class, this.jobId)).isEqualTo("Queue");

        this.pass();

        Map<String, Object> done = this.run(run);
        assertThat(done.get("job_status")).isEqualTo("Completed");
        assertThat(done.get("job_status_message")).isEqualTo("4 of 4 step(s) completed (2 reused from attempt 1).");
        assertThat(ran("check")).isEqualTo(shapeBefore + 1);
        List<Map<String, Object>> rows = login.queryForList("SELECT attempt, step_key, status, reused_from IS NOT NULL AS reused, "
            + "started_at IS NULL AS no_times, tries, records_out, pipeline_definition_id FROM step_execution WHERE job_queue_id = ? "
            + "ORDER BY attempt, step_index", run);
        assertThat(rows).extracting(r -> r.get("attempt") + ":" + r.get("step_key") + ":" + r.get("status") + ":" + r.get("reused")
            + ":" + r.get("no_times") + ":" + r.get("tries")).containsExactly(
            "1:read:Completed:false:false:1", "1:shape:Completed:false:false:1", "1:check:Failed:false:false:1", "1:after:Skip:false:true:0",
            "2:read:Completed:true:true:0", "2:shape:Completed:true:true:0", "2:check:Completed:false:false:1",
            "2:after:Completed:false:false:1");
        assertThat(rows.subList(4, 8)).extracting(r -> r.get("pipeline_definition_id")).containsOnly(fixed);
        assertThat(login.queryForList("SELECT s2.reused_from = s1.step_execution_id FROM step_execution s2 JOIN step_execution s1 "
            + "ON s1.job_queue_id = s2.job_queue_id AND s1.attempt = 1 AND s1.step_key = s2.step_key WHERE s2.job_queue_id = ? "
            + "AND s2.attempt = 2 AND s2.reused_from IS NOT NULL", Boolean.class, run)).containsExactly(true, true);
        assertThat(login.queryForObject("SELECT min(ended_at) FROM step_execution WHERE job_queue_id = ? AND attempt = 1 AND step_index < 2",
            Object.class, run)).as("attempt 1's steps were not touched").isEqualTo(firstSteps.get("ended"));
        assertThat(login.queryForList("SELECT d.storage_key FROM run_dataset d JOIN step_execution s ON s.step_execution_id = "
            + "d.step_execution_id WHERE s.job_queue_id = ? AND s.attempt = 2 ORDER BY s.step_index", String.class, run))
            .as("attempt 2 wrote only its re-run steps' outputs").containsExactly("datasets/" + run + "/2/check/output.rows",
                "datasets/" + run + "/2/after/output.rows");
        assertThat(Files.exists(datasetDir.resolve("datasets/" + run + "/2/shape"))).isFalse();
        List<String> audit = login.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ? ORDER BY job_audit_log_id",
            String.class, run);
        assertThat(audit).contains("Attempt 2 queued by user " + ADMIN + ": retry from step 3 <check>, reusing 2 step(s) of attempt 1.",
            "Resuming from step 3 <check>: 2 step(s) reused from attempt 1 (read, shape), running check, after.",
            "Step 1/4 <read> (sample) reused from attempt 1: not run again.",
            "Step 2/4 <shape> (select) reused from attempt 1: not run again.");

        // The attempt history: who asked, how, from where.
        Map<String, Object> attempt = login.queryForMap("SELECT attempt, previous_attempt, mode, from_step_index, from_step_key, "
            + "reused_steps, pipeline_definition_id, requested_via, requested_by_user_id, tenant_id FROM run_attempt WHERE job_queue_id = ?", run);
        assertThat(attempt).containsEntry("attempt", 2).containsEntry("previous_attempt", 1).containsEntry("mode", "resume")
            .containsEntry("from_step_index", 2).containsEntry("from_step_key", "check").containsEntry("reused_steps", 2)
            .containsEntry("pipeline_definition_id", fixed).containsEntry("requested_via", "console")
            .containsEntry("requested_by_user_id", ADMIN).containsEntry("tenant_id", TENANT);
        asAdminOf(TENANT, ADMIN);
        ResponseDto history = this.timeline.timeline(run, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>) history.getData();
        assertThat(read.get("attempts")).isEqualTo(Arrays.asList(1, 2));
        assertThat(read.get("attemptDetails").toString()).contains("attempt=2", "mode=resume", "fromStepKey=check",
            "requestedVia=console", "requestedByUserId=" + ADMIN);
        assertThat(read.get("steps").toString()).contains("reusedFromAttempt=1");

        // Billed once: both attempts' outcomes go to the meter under the run's own key, which the meter takes once.
        assertThat(this.metered).filteredOn(e -> Meter.PIPELINE_RUNS.key().equals(e.meter) && e.jobQueueId != null && e.jobQueueId == run)
            .extracting(e -> e.dedupeKey).containsExactly("run#" + run, "run#" + run);
    }

    @Test
    void aChangeBeforeTheFailedStepIsRefusedWithItsReasonAndNothingIsQueued() throws Exception {
        long run = this.failedAtStepThree();
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 2, ?::json)", this.pipelineKey,
            definition(true, "order_id"));

        asAdminOf(TENANT, ADMIN);
        ResponseDto refused = this.timeline.retry(run, "failed_step");

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).isEqualTo("The pipeline changed before the failed step <check> since this run (version 1, now 2): "
            + "step 2 <shape>'s settings. Only the failed step and the steps after it may change; run it again from the start.");
        assertThat(this.run(run).get("attempt")).isEqualTo(1);
        assertThat(this.run(run).get("job_status")).isEqualTo("Failed");
        assertThat(login.queryForObject("SELECT count(*) FROM run_attempt WHERE job_queue_id = ?", Long.class, run)).isZero();

        // An administrator may still run it again from the start, on the changed pipeline.
        ResponseDto restart = this.timeline.retry(run, "start");
        assertThat(restart.getStatus()).as(restart.getMessage()).isEqualTo("SUCCESS");
        TenantContext.clear();
        this.pass();
        assertThat(this.run(run).get("job_status")).isEqualTo("Completed");
        assertThat(login.queryForObject("SELECT mode FROM run_attempt WHERE job_queue_id = ?", String.class, run)).isEqualTo("restart");
        assertThat(login.queryForObject("SELECT count(*) FROM step_execution WHERE job_queue_id = ? AND attempt = 2 AND reused_from IS NULL "
            + "AND started_at IS NOT NULL", Long.class, run)).as("every step ran").isEqualTo(4L);
    }

    @Test
    void anotherWorkspaceSeesNoAttemptAndCannotRetryTheRun() throws Exception {
        long run = this.failedAtStepThree();
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 2, ?::json)", this.pipelineKey,
            definition(true, "id"));

        asAdminOf(OTHER, OTHER_ADMIN);
        assertThat(this.timeline.retryPlan(run).getMessage()).isEqualTo("Run not found with jobQueueId.");
        ResponseDto theirs = this.timeline.retry(run, "start");
        assertThat(theirs.getStatus()).isEqualTo("ERROR");
        assertThat(theirs.getMessage()).isEqualTo("Run not found with jobQueueId.");
        assertThat(this.run(run).get("attempt")).as("nothing queued").isEqualTo(1);
        TenantContext.clear();

        asAdminOf(TENANT, ADMIN);
        assertThat(this.timeline.retry(run, "failed_step").getStatus()).isEqualTo("SUCCESS");
        TenantContext.clear();
        this.pass();
        JdbcTemplate app = db.appJdbc();
        assertThat(RowSecurity.forTenant(TENANT, () -> app.queryForObject("SELECT count(*) FROM run_attempt WHERE job_queue_id = ?",
            Long.class, run))).isEqualTo(1L);
        assertThat(RowSecurity.forTenant(OTHER, () -> app.queryForObject("SELECT count(*) FROM run_attempt", Long.class))).isZero();
        assertThat(RowSecurity.forTenant(OTHER, () -> app.queryForObject("SELECT count(*) FROM step_execution WHERE reused_from IS NOT NULL",
            Long.class))).isZero();
    }

    @Test
    void theSweepLeavesTheKeptOutputsOfARunInFlightAlone() throws Exception {
        long run = this.failedAtStepThree();
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 2, ?::json)", this.pipelineKey,
            definition(true, "id"));
        asAdminOf(TENANT, ADMIN);
        assertThat(this.timeline.retry(run, "failed_step").getStatus()).isEqualTo("SUCCESS");
        TenantContext.clear();
        // Queued to resume; its kept outputs pass their expiry before it starts.
        login.update("UPDATE run_dataset SET expires_at = now() - interval '1 minute' WHERE step_execution_id IN (SELECT step_execution_id "
            + "FROM step_execution WHERE job_queue_id = ?)", run);

        DatasetSweep sweep = AcrossTenantsProxy.of(new DatasetSweep(db.appJdbc(), new FileDatasetStore(datasetDir.toString())));
        assertThat(sweep.sweep()).as("not under a run in flight").isZero();
        assertThat(Files.exists(datasetDir.resolve("datasets/" + run + "/1/shape/output.rows"))).isTrue();

        // Started anyway, it declines with the reason rather than fail a step on a missing file.
        this.pass();
        assertThat((String) this.run(run).get("job_status_message")).startsWith("Declined by the step engine: attempt 2 cannot resume from "
            + "step 3: the kept output of step <read> expired at ");
        assertThat(sweep.sweep()).as("the run has ended: now they go").isGreaterThan(0);
    }
}
