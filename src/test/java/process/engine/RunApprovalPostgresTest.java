package process.engine;

import org.barco.platform.meter.MeterReporter;
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
import process.pipeline.approval.JdbcRunApprovalStore;
import process.pipeline.approval.RunApprovalEvents;
import process.pipeline.approval.RunApprovals;
import process.outbox.OutboxWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import process.security.TenantContext;
import process.util.OpenSearchAuditLogClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import process.pipeline.tasks.ApprovalStepTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-346 against a real etl_job as the application runs it (process_app under row security): a run reaches its Approval
 * step and waits -- Running, its step Running and saying so, one run_approval request and one run-approval.requested event in
 * the outbox, left alone by the stall sweep -- until the Task inbox decides. Approved, the engine continues it from the next
 * step on what the steps before kept (they are not run again) and completes it; rejected, it fails with the reviewer's
 * reason and the rest is skipped. A redelivered decision moves nothing. Retry from the failed step works around the
 * approval: a failure after it reuses the approved step (no second request); a rejection retried asks again. A request
 * nobody decides expires (the run fails), and one whose run was closed is cancelled; both close the inbox task.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres).
 */
class RunApprovalPostgresTest {

    private static final long TENANT = 7971L;
    private static final long OTHER = 7972L;
    private static final long ADMIN = 79711L;
    private static final long REVIEWER = 79712L;
    private static final long TYPE = 79710L;
    private static long nextPipeline = 79720L;
    private long pipelineKey;
    private long jobId;
    private static final String PAYLOAD = "<pipeline><order>7</order></pipeline>";

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static JdbcTemplate login;
    private static Path datasetDir;
    private static long nextRun = 797100;

    private static final Map<String, AtomicInteger> RAN = new ConcurrentHashMap<>();

    private ThreadPoolExecutor runThreads;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private PreDispatchPhase phase;
    private RunRetries retries;
    private StepTimelineService timeline;
    private RunApprovals approvals;
    private TransactionServiceImpl store;

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

    /** Fails unless its config says pass. */
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

    /** read, shape, approve (an Approval step), check (a gate), after. */
    private static String definition(boolean pass) {
        return "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":{\"rows\":[{\"id\":1,\"amount\":50},"
            + "{\"id\":2,\"amount\":120},{\"id\":3,\"amount\":400}]}},{\"key\":\"shape\",\"task\":\"select\",\"config\":{\"columns\":"
            + "[\"id\",\"amount\"]}},{\"key\":\"approve\",\"task\":\"approval\",\"config\":{\"title\":\"Send {{rows}} orders?\","
            + "\"fields\":{\"Largest\":\"{{amount}}\"},\"expiresAfterHours\":4}},{\"key\":\"check\",\"task\":\"gate\",\"config\":{\"pass\":" + pass
            + "}},{\"key\":\"after\",\"task\":\"counted\"}]}";
    }

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("run_approval");
        login = db.jdbc();
        jpa = new ScratchJpa(db.appPool());
        datasetDir = Files.createTempDirectory("etl-datasets-approval");
        for (long tenant : new long[] {TENANT, OTHER}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
                "T" + tenant);
        }
        login.update("INSERT INTO source_task_type (source_task_type_id, tenant_id, service_name, description, queue_topic_partition, "
            + "task_type_status) VALUES (?, ?, 'orders', 'orders', 'topic=etl.orders&partitions=[*]', 'Active')", TYPE, TENANT);
    }

    private void ownPipeline(boolean pass) {
        long key = ++nextPipeline * 10;
        String id = "APPROVE-" + key;
        this.pipelineKey = key;
        this.jobId = key + 3;
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status, source_task_type_id) "
            + "VALUES (?, ?, ?, ?, 'Active', ?)", key, TENANT, id, id, TYPE);
        login.update("INSERT INTO source_task (task_detail_id, tenant_id, source_task_type_id, task_name, task_payload, task_status, "
            + "pipeline_id, date_created) VALUES (?, ?, ?, 'orders', ?, 'Active', ?, now())", key + 2, TENANT, TYPE, PAYLOAD, id);
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, complete_job, fail_job, skip_job, max_attempts, retry_backoff_seconds) "
            + "VALUES (?, now(), 'Manual', 'orders', 'Active', 'Queue', 1, ?, ?, false, false, false, 1, 1)", key + 3, TENANT, key + 2);
        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 1, ?::json)", key, definition(pass));
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
        this.store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(this.store, mock(NotificationPort.class));
        NotifyServiceImpl callback = new NotifyServiceImpl(bulkAction, mock(JobMail.class), this.store, mock(NotificationPort.class));
        ReflectionTestUtils.setField(callback, "meter", mock(MeterReporter.class));
        NotifyService notify = transactional(callback);
        StepTasks tasks = Definitions.builtInTasks(new Counted(), new Gate(), new ApprovalStepTask());
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
        JdbcRunApprovalStore requests = new JdbcRunApprovalStore(db.appJdbc());
        RunApprovalEvents events = new RunApprovalEvents(new OutboxWriter(db.appJdbc()));
        StepEngine engine = new StepEngine(definitions, steps, tasks, new DefinitionValidator(tasks),
            new FileDatasetStore(datasetDir.toString()), notify, this.store, new TransactionTemplate(jpa.transactionManager()), this.runThreads,
            Executors.newCachedThreadPool(), duration -> { });
        engine.useAttempts(attempts);
        engine.useApprovals(requests, events);
        PreDispatchPhase bare = new PreDispatchPhase(this.store, bulkAction, new AiStepService(jpa.repository(PipelineRepository.class),
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
        this.timeline.useApprovals(requests);
        this.approvals = new RunApprovals(requests, events, engine, jpa.repository(JobQueueRepository.class),
            jpa.repository(SourceJobRepository.class), this.store, jpa.transactionManager());
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

    /** Waits for whatever the engine was handed: a run, or a run's continuation. */
    private void settle() throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (this.finished.get() < this.submitted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(this.finished.get()).as("the engine finished its work").isEqualTo(this.submitted.get());
    }

    private void pass() throws Exception {
        this.phase.runPass();
        this.settle();
    }

    private Map<String, Object> run(long id) {
        return login.queryForMap("SELECT job_status, attempt, job_status_message FROM job_queue WHERE job_queue_id = ?", id);
    }

    private List<String> steps(long run) {
        return login.queryForList("SELECT attempt || ':' || step_key || ':' || status || ':' || (reused_from IS NOT NULL) FROM step_execution "
            + "WHERE job_queue_id = ? ORDER BY attempt, step_index", String.class, run);
    }

    private Map<String, Object> request(long run, int attempt) {
        return login.queryForMap("SELECT run_approval_id, tenant_id, status, title, reviewers_kind, reviewers_value, records_in, step_key, "
            + "step_index, decided_by_user_id, decided_by_name, comment, workflow_instance_id, expires_at > requested_at AS expires_later "
            + "FROM run_approval WHERE job_queue_id = ? AND attempt = ?", run, attempt);
    }

    private List<String> outbox(long run) {
        return login.queryForList("SELECT event::json ->> 'eventType' FROM platform_outbox WHERE topic = 'platform.process.run-review.v1' "
            + "AND message_key = ? ORDER BY 1", String.class, String.valueOf(run));
    }

    private static int ran(String key) {
        AtomicInteger count = RAN.get(key);
        return count == null ? 0 : count.get();
    }

    private long waiting(boolean pass) throws Exception {
        this.ownPipeline(pass);
        long run = this.queue();
        this.pass();
        return run;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunWaitsAtItsApprovalStepAndContinuesWhenApprovedInTheInbox() throws Exception {
        int afterBefore = ran("after");
        long run = this.waiting(true);

        Map<String, Object> paused = this.run(run);
        assertThat(paused.get("job_status")).as(paused + " " + this.steps(run) + " " + login.queryForList("SELECT status_message FROM "
            + "step_execution WHERE job_queue_id = ?", String.class, run)).isEqualTo("Running");
        assertThat(paused.get("job_status_message")).isEqualTo("Waiting for approval at step 3 <approve>: Send 3 orders?");
        assertThat(this.steps(run)).containsExactly("1:read:Completed:false", "1:shape:Completed:false", "1:approve:Running:false",
            "1:check:Queue:false", "1:after:Queue:false");
        assertThat(login.queryForObject("SELECT status_message FROM step_execution WHERE job_queue_id = ? AND step_key = 'approve'",
            String.class, run)).isEqualTo("Waiting for approval: Send 3 orders?");
        Map<String, Object> request = this.request(run, 1);
        assertThat(request).containsEntry("tenant_id", TENANT).containsEntry("status", "PENDING").containsEntry("title", "Send 3 orders?")
            .containsEntry("reviewers_kind", "role").containsEntry("reviewers_value", "TENANT_ADMIN").containsEntry("records_in", 3L)
            .containsEntry("step_index", 2).containsEntry("expires_later", true);
        assertThat(this.outbox(run)).containsExactly("run-approval.requested");
        Map<String, Object> event = login.queryForMap("SELECT event::json -> 'payload' ->> 'title' AS title, event::json -> 'payload' -> "
            + "'fields' ->> 'Largest' AS largest, event::json -> 'payload' ->> 'link' AS link, event::json ->> 'tenantId' AS tenant "
            + "FROM platform_outbox WHERE message_key = ?", String.valueOf(run));
        assertThat(event).containsEntry("title", "Send 3 orders?").containsEntry("largest", "50").containsEntry("tenant", String.valueOf(TENANT))
            .containsEntry("link", "/pipelines/schedules/" + this.jobId + "/runs/" + run + "/logs");
        assertThat(ran("after")).isEqualTo(afterBefore);
        // The stall sweep leaves a waiting run alone, however long it waits.
        List<Long> stalled = RowSecurity.acrossTenants("test", () -> this.store.findStalledRuns(LocalDateTime.now().plusDays(30)).stream()
            .map(q -> q.getJobQueueId()).collect(Collectors.toList()));
        assertThat(stalled).doesNotContain(run);
        // The timeline says so.
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@example.test");
        Map<String, Object> read = (Map<String, Object>) this.timeline.timeline(run, null).getData();
        TenantContext.clear();
        assertThat(read.get("waitingForApproval")).isEqualTo(true);
        assertThat(read.get("steps").toString()).contains("approval={approvalId=" + request.get("run_approval_id") + ", status=PENDING, waiting=true");

        long id = ((Number) request.get("run_approval_id")).longValue();
        RunApprovals.Outcome outcome = this.approvals.decideFromInbox(TENANT, id, true, REVIEWER, "Ana Ruiz", "  totals match  ", 901L, 902L);
        assertThat(outcome.moved).as(outcome.message).isTrue();
        this.settle();

        Map<String, Object> done = this.run(run);
        assertThat(done.get("job_status")).isEqualTo("Completed");
        assertThat(done.get("job_status_message")).isEqualTo("5 of 5 step(s) completed.");
        assertThat(this.steps(run)).containsExactly("1:read:Completed:false", "1:shape:Completed:false", "1:approve:Completed:false",
            "1:check:Completed:false", "1:after:Completed:false");
        assertThat(login.queryForObject("SELECT status_message FROM step_execution WHERE job_queue_id = ? AND step_key = 'approve'",
            String.class, run)).isEqualTo("Approved by Ana Ruiz (user " + REVIEWER + "): totals match.");
        assertThat(login.queryForObject("SELECT bulk_id FROM run_approval WHERE run_approval_id = ?", String.class, id))
            .as("a single decision names no bulk decision").isNull();
        assertThat(this.request(run, 1)).containsEntry("status", "APPROVED").containsEntry("decided_by_user_id", REVIEWER)
            .containsEntry("comment", "totals match").containsEntry("workflow_instance_id", 901L);
        assertThat(ran("after")).isEqualTo(afterBefore + 1);
        assertThat(login.queryForObject("SELECT records_out FROM step_execution WHERE job_queue_id = ? AND step_key = 'after'", Long.class, run))
            .as("the steps after it read what the steps before it kept").isEqualTo(3L);
        List<String> audit = login.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ? ORDER BY job_audit_log_id",
            String.class, run);
        assertThat(audit).anyMatch(line -> line.startsWith("Step 3/5 <approve> (approval) waits for approval by the workspace's administrators"))
            .contains("Approval request " + id + " at step <approve>: approved by Ana Ruiz (user " + REVIEWER + ") in the Task inbox "
                + "(request 901, task 902): totals match.");

        // Redelivered: decided already, nothing moves.
        RunApprovals.Outcome again = this.approvals.decideFromInbox(TENANT, id, false, REVIEWER, "Ana Ruiz", "no", 901L, 902L);
        assertThat(again.moved).isFalse();
        this.settle();
        assertThat(this.run(run).get("job_status")).isEqualTo("Completed");
        // Another workspace cannot decide it either (it is not found there).
        assertThat(this.approvals.decideFromInbox(OTHER, id, true, 1L, "x", null, 1L, 1L).moved).isFalse();
    }

    @Test
    void aRejectionFailsTheRunWithTheReasonAndARetryAsksAgain() throws Exception {
        long run = this.waiting(true);
        long id = ((Number) this.request(run, 1).get("run_approval_id")).longValue();
        // Bulk review: decided in a bulk decision of the Task inbox, the request and the audit line name it.
        assertThat(this.approvals.decideFromInbox(TENANT, id, false, REVIEWER, "Ana Ruiz", "amounts look off", 911L, 912L,
            "bulk-7a1c-0001").moved).isTrue();
        assertThat(login.queryForObject("SELECT bulk_id FROM run_approval WHERE run_approval_id = ?", String.class, id))
            .isEqualTo("bulk-7a1c-0001");
        assertThat(login.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ? ORDER BY job_audit_log_id",
            String.class, run)).contains("Approval request " + id + " at step <approve>: rejected by Ana Ruiz (user " + REVIEWER
                + ") in the Task inbox (request 911, task 912): amounts look off. Bulk decision bulk-7a1c-0001.");
        this.settle();
        Map<String, Object> failed = this.run(run);
        assertThat(failed.get("job_status")).isEqualTo("Failed");
        assertThat(failed.get("job_status_message")).isEqualTo("Step 3 <approve> failed: Rejected by Ana Ruiz (user " + REVIEWER
            + "): amounts look off");
        assertThat(this.steps(run)).containsExactly("1:read:Completed:false", "1:shape:Completed:false", "1:approve:Failed:false",
            "1:check:Skip:false", "1:after:Skip:false");

        // Retry from the failed step: the approval step runs again, so it asks again -- a new request for attempt 2.
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@example.test");
        ResponseDto asked = this.timeline.retry(run, "failed_step");
        TenantContext.clear();
        assertThat(asked.getStatus()).as(asked.getMessage()).isEqualTo("SUCCESS");
        this.pass();
        assertThat(this.run(run).get("job_status")).isEqualTo("Running");
        Map<String, Object> second = this.request(run, 2);
        assertThat(second).containsEntry("status", "PENDING");
        assertThat(this.approvals.decideFromInbox(TENANT, ((Number) second.get("run_approval_id")).longValue(), true, REVIEWER, "Ana Ruiz",
            null, 913L, 914L).moved).isTrue();
        this.settle();
        assertThat(this.run(run).get("job_status")).isEqualTo("Completed");
        assertThat(this.run(run).get("job_status_message")).isEqualTo("5 of 5 step(s) completed (2 reused from attempt 1).");
        assertThat(this.steps(run).subList(5, 10)).containsExactly("2:read:Completed:true", "2:shape:Completed:true",
            "2:approve:Completed:false", "2:check:Completed:false", "2:after:Completed:false");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailureAfterTheApprovalIsRetriedFromThereWithoutAskingAgain() throws Exception {
        long run = this.waiting(false);
        long id = ((Number) this.request(run, 1).get("run_approval_id")).longValue();
        assertThat(this.approvals.decideFromInbox(TENANT, id, true, REVIEWER, "Ana Ruiz", "Checked.", 921L, 922L).moved).isTrue();
        this.settle();
        assertThat(login.queryForObject("SELECT status_message FROM step_execution WHERE job_queue_id = ? AND step_key = 'approve'",
            String.class, run)).as("one full stop").isEqualTo("Approved by Ana Ruiz (user " + REVIEWER + "): Checked.");
        assertThat(this.run(run).get("job_status")).isEqualTo("Failed");
        assertThat(this.run(run).get("job_status_message")).isEqualTo("Step 4 <check> failed: 2 row(s) break the contract");

        login.update("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, 2, ?::json)", this.pipelineKey,
            definition(true));
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@example.test");
        ResponseDto asked = this.timeline.retry(run, "failed_step");
        TenantContext.clear();
        assertThat(asked.getMessage()).isEqualTo("Run " + run + " queued to resume from step 4 <check> as attempt 2, reusing 3 step(s).");
        this.pass();
        assertThat(this.run(run).get("job_status")).isEqualTo("Completed");
        assertThat(this.steps(run).subList(5, 10)).containsExactly("2:read:Completed:true", "2:shape:Completed:true",
            "2:approve:Completed:true", "2:check:Completed:false", "2:after:Completed:false");
        assertThat(login.queryForObject("SELECT count(*) FROM run_approval WHERE job_queue_id = ?", Long.class, run))
            .as("the approval is reused, not asked again").isEqualTo(1L);
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "admin@example.test");
        Map<String, Object> read = (Map<String, Object>) this.timeline.timeline(run, 2).getData();
        TenantContext.clear();
        assertThat(read.get("waitingForApproval")).isEqualTo(false);
        assertThat(read.get("steps").toString()).contains("status=APPROVED", "decidedBy=Ana Ruiz (user " + REVIEWER + ")");
    }

    @Test
    void anUndecidedRequestExpiresAndOneWhoseRunWasClosedIsCancelled() throws Exception {
        long late = this.waiting(true);
        login.update("UPDATE run_approval SET expires_at = now() - interval '1 minute' WHERE job_queue_id = ?", late);
        long closed = this.waiting(true);
        login.update("UPDATE job_queue SET job_status = 'Failed', job_status_message = 'Marked failed by an operator' WHERE job_queue_id = ?",
            closed);

        int[] swept = this.approvals.sweep(Instant.now());
        this.settle();
        assertThat(swept).containsExactly(1, 1, 0);
        assertThat(this.request(late, 1)).containsEntry("status", "EXPIRED");
        Map<String, Object> expired = this.run(late);
        assertThat(expired.get("job_status")).isEqualTo("Failed");
        assertThat((String) expired.get("job_status_message")).startsWith("Step 3 <approve> failed: No decision by ")
            .endsWith(": the approval request expired.");
        assertThat(this.outbox(late)).containsExactly("run-approval.closed", "run-approval.requested");
        assertThat(this.request(closed, 1)).containsEntry("status", "CANCELLED");
        assertThat(this.run(closed).get("job_status_message")).isEqualTo("Marked failed by an operator");
        assertThat(this.outbox(closed)).containsExactly("run-approval.closed", "run-approval.requested");
        // Decided after it was cancelled: nothing moves.
        long id = ((Number) this.request(closed, 1).get("run_approval_id")).longValue();
        assertThat(this.approvals.decideFromInbox(TENANT, id, true, REVIEWER, "Ana Ruiz", null, 1L, 1L).moved).isFalse();
        assertThat(this.approvals.sweep(Instant.now())).containsExactly(0, 0, 0);
    }

    @Test
    void aDecisionARestartLostOnItsWayToTheEngineIsHandedOnByTheSweepOnce() throws Exception {
        // 2026-10-21, the patient-episode resilience test: the Task inbox's decision was recorded, and Core restarted before its
        // step engine ran it -- the run waited at an approval already given, and nothing ever moved it.
        int afterBefore = ran("after");
        long run = this.waiting(true);
        long id = ((Number) this.request(run, 1).get("run_approval_id")).longValue();
        RowSecurity.forTenant(TENANT, () -> new JdbcRunApprovalStore(db.appJdbc()).decide(id, "APPROVED", REVIEWER, "Ana Ruiz", null, 1L, 1L,
            Instant.now(), null));
        assertThat(this.approvals.sweep(Instant.now())).as("decided just now: on its way, not lost").containsExactly(0, 0, 0);

        login.update("UPDATE run_approval SET decided_at = now() - interval '2 minutes' WHERE run_approval_id = ?", id);
        assertThat(this.approvals.sweep(Instant.now())).containsExactly(0, 0, 1);
        this.settle();
        assertThat(this.run(run).get("job_status")).isEqualTo("Completed");
        assertThat(ran("after")).isEqualTo(afterBefore + 1);
        assertThat(this.approvals.sweep(Instant.now())).as("handed on once").containsExactly(0, 0, 0);
    }

    @Test
    void anApprovalStepIsSettledOnceHoweverOftenItsDecisionIsHandedOn() throws Exception {
        long run = this.waiting(true);
        long step = login.queryForObject("SELECT step_execution_id FROM step_execution WHERE job_queue_id = ? AND step_key = 'approve'",
            Long.class, run);
        JdbcStepStore steps = new JdbcStepStore(db.appJdbc());
        assertThat(RowSecurity.forTenant(TENANT, () -> steps.settleWaiting(step, "Completed", 3L, null, "Approved"))).isTrue();
        assertThat(RowSecurity.forTenant(TENANT, () -> steps.settleWaiting(step, "Completed", 3L, null, "Approved again"))).isFalse();
    }

    @Test
    void anotherWorkspaceSeesNoneOfTheRequests() throws Exception {
        long run = this.waiting(true);
        JdbcTemplate app = db.appJdbc();
        assertThat(RowSecurity.forTenant(TENANT, () -> app.queryForObject("SELECT count(*) FROM run_approval WHERE job_queue_id = ?",
            Long.class, run))).isEqualTo(1L);
        assertThat(RowSecurity.forTenant(OTHER, () -> app.queryForObject("SELECT count(*) FROM run_approval", Long.class))).isZero();
    }
}
