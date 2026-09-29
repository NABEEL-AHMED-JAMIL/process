package process.api;

import process.ai.JdbcModelChoiceStore;
import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import process.AcrossTenantsProxy;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.engine.BulkAction;
import process.identity.InternalRunVerificationRestApi;
import process.model.dto.SourceJobQueueDto;
import process.model.pojo.JobQueue;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.impl.NotifyServiceImpl;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.RunCallbackTokens;
import process.security.RunWorkspace;
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-258: a worker's callback carries no person's token, so nothing sets a workspace on its thread, and under row
 * security a session with none sees no run at all. Each callback endpoint reads its run's workspace (RunWorkspace,
 * across workspaces, by the run's id) and does everything else as that workspace. Run here as the application runs
 * it -- process_app, nobody signed in -- with the endpoints wired as Spring wires them, and once without RunWorkspace
 * to show what row security does to a callback that is not scoped.
 */
class WorkerCallbackRowSecurityPostgresTest {

    static final long A = 8801L;
    static final long B = 8802L;
    static final long A_JOB = 88011L;
    static final long B_JOB = 88021L;
    static final long A_RUN = 880111L;
    static final long B_RUN = 880211L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private static RunCallbackTokens tokens;
    private static RunWorkspace runWorkspace;
    private static String aToken;
    private static String bToken;

    @BeforeAll
    static void create() throws Exception {
        db = ScratchPostgres.create("callback_rls");
        JdbcTemplate login = db.jdbc();
        for (long[] w : new long[][]{{A, A_JOB, A_RUN}, {B, B_JOB, B_RUN}}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", w[0], "w" + w[0], "W" + w[0]);
            login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, complete_job, "
                + "fail_job, skip_job) VALUES (?, ?, 'Manual', 'callback', 'Active', 1, ?, false, false, false)", w[1],
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), w[0]);
            login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, job_send, attempt) "
                + "VALUES (?, ?, 'Running', now(), now(), 'Active', true, 1)", w[2], w[1]);
        }
        jpa = new ScratchJpa(db.appPool());
        tokens = transactional(new RunCallbackTokens(jpa.repository(JobQueueRepository.class), 24, ""));
        runWorkspace = AcrossTenantsProxy.of(new RunWorkspace(db.appJdbc()));
        // Minted as the dispatcher mints them: across workspaces.
        aToken = RowSecurity.acrossTenants("test: the dispatcher mints", () -> jpa.transactions().execute(tx -> tokens.issue(run(A_RUN))));
        bToken = RowSecurity.acrossTenants("test: the dispatcher mints", () -> jpa.transactions().execute(tx -> tokens.issue(run(B_RUN))));
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) jpa.close();
        if (db != null) db.close();
    }

    /** The bean behind its @Transactional, as Spring hands it out (the tests build them by hand). */
    @SuppressWarnings("unchecked")
    private static <T> T transactional(T bean) {
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(jpa.transactionManager(), new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private static JobQueue run(long id) {
        return jpa.repository(JobQueueRepository.class).findById(id).orElseThrow(IllegalStateException::new);
    }

    private static MeterRestApi meter(boolean scoped) {
        MeterRestApi api = new MeterRestApi(tokens, jpa.repository(JobQueueRepository.class), jpa.repository(SourceJobRepository.class));
        if (scoped) {
            api.setRunWorkspace(runWorkspace);
        }
        return api;
    }

    private static MeterRestApi.VerifyRunDto dto(long job, long run) {
        MeterRestApi.VerifyRunDto dto = new MeterRestApi.VerifyRunDto();
        dto.jobId = job;
        dto.jobQueueId = run;
        return dto;
    }

    @Test
    void theRunsWorkspaceIsReadWithNobodySignedIn() {
        assertThat(runWorkspace.of(A_RUN)).isEqualTo(A);
        assertThat(runWorkspace.of(B_RUN)).isEqualTo(B);
        assertThat(runWorkspace.of(999999L)).as("no such run: no workspace").isZero();
    }

    @Test
    void aWorkersUsageReportIsVerifiedAsItsRunsWorkspace() {
        ResponseEntity<?> answer = meter(true).verifyRun(aToken, dto(A_JOB, A_RUN));
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answer.getBody().toString()).contains("\"tenantId\":" + A);
    }

    /** Without the run's workspace the session has none, sees no run, and the honest worker is refused. */
    @Test
    void unscopedTheSameReportIsRefusedBecauseTheRunIsInvisible() {
        assertThat(meter(false).verifyRun(aToken, dto(A_JOB, A_RUN)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anotherRunsTokenOpensNothing() {
        assertThat(meter(true).verifyRun(aToken, dto(B_JOB, B_RUN)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(meter(true).verifyRun(bToken, dto(A_JOB, A_RUN)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void coresVerifyCallbackAnswersForTheRunsWorkspace() {
        InternalRunVerificationRestApi api = new InternalRunVerificationRestApi(tokens, jpa.repository(JobQueueRepository.class),
            jpa.repository(SourceJobRepository.class), jpa.repository(PipelineRepository.class), new JdbcModelChoiceStore(db.appJdbc()),
            "internal-token");
        api.setRunWorkspace(runWorkspace);
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", B_JOB);
        body.put("token", bToken);
        body.put("variant", "callback");
        @SuppressWarnings("unchecked")
        Map<String, Object> verdict = (Map<String, Object>) api.verifyCallback("internal-token", B_RUN, body).getBody();
        assertThat(verdict).containsEntry("valid", true).containsEntry("tenantId", B);
    }

    /**
     * MIG-242: the model a worker step runs on is read from run_ai_step -- a tenant table since V181 -- as the run's
     * workspace, so the callback still finds what the run was prepared with under row security.
     */
    @Test
    @SuppressWarnings("unchecked")
    void coresVerifyCallbackReadsTheWorkerStepsModelAsTheRunsWorkspace() {
        JdbcTemplate login = db.jdbc();
        login.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status) "
            + "VALUES (97801, ?, 'RLS-PIPE', 'rls pipeline', 'Active')", B);
        login.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, field_type, tenant_id, prompt_id, run_in, "
            + "position) VALUES (97802, 97801, 'caption', 'AI caption', 'ai', ?, 5002, 'worker', 1)", B);
        login.update("INSERT INTO source_task (task_detail_id, task_name, task_status, task_payload, tenant_id, pipeline_id) "
            + "VALUES (97803, 'rls task', 'Active', '<pipeline/>', ?, 'RLS-PIPE')", B);
        login.update("UPDATE source_job SET task_detail_id = 97803 WHERE job_id = ?", B_JOB);
        login.update("INSERT INTO run_ai_step (job_queue_id, step_key, run_in, outcome, model_profile, profile_source) "
            + "VALUES (?, 'caption', 'worker', 'handed', '2204', 'run')", B_RUN);
        try {
            InternalRunVerificationRestApi api = new InternalRunVerificationRestApi(tokens, jpa.repository(JobQueueRepository.class),
                jpa.repository(SourceJobRepository.class), jpa.repository(PipelineRepository.class),
                new JdbcModelChoiceStore(db.appJdbc()), "internal-token");
            api.setRunWorkspace(runWorkspace);
            Map<String, Object> body = new HashMap<>();
            body.put("jobId", B_JOB);
            body.put("token", bToken);
            body.put("variant", "callback");

            Map<String, Object> verdict = (Map<String, Object>) api.verifyCallback("internal-token", B_RUN, body).getBody();

            List<Map<String, Object>> steps = (List<Map<String, Object>>) verdict.get("workerSteps");
            assertThat(steps).hasSize(1);
            assertThat(steps.get(0)).containsEntry("stepTag", "caption").containsEntry("modelProfile", "2204")
                .containsEntry("sourceTaskId", 97803L);
        } finally {
            login.update("DELETE FROM run_ai_step WHERE job_queue_id = ?", B_RUN);
            login.update("UPDATE source_job SET task_detail_id = NULL WHERE job_id = ?", B_JOB);
            login.update("DELETE FROM source_task WHERE task_detail_id = 97803");
            login.update("DELETE FROM pipeline_field WHERE pipeline_field_id = 97802");
            login.update("DELETE FROM pipeline WHERE pipeline_key = 97801");
        }
    }

    /** A log line lands, under its run's workspace, through the whole callback path. */
    @Test
    void aWorkersLogLineLandsInItsRunsWorkspace() {
        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        NotifyServiceImpl service = new NotifyServiceImpl(new BulkAction(store, mock(NotificationPort.class)), mock(JobMail.class), store,
            mock(NotificationPort.class));
        // Behind its @Transactional as the application has it: the transaction begins and commits inside the
        // controller's scope, so its writes -- flushed at commit -- run as the run's workspace.
        NotifyResetApi api = new NotifyResetApi(transactional(service), tokens);
        api.setRunWorkspace(runWorkspace);
        SourceJobQueueDto line = new SourceJobQueueDto();
        line.setJobStatusMessage("rls callback line");
        ResponseEntity<?> answer = api.addLogs(A_JOB, A_RUN, aToken, null, null, line);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.jdbc().queryForList("SELECT tenant_id FROM job_audit_logs WHERE log_detail = 'rls callback line'", Long.class))
            .isEqualTo(Collections.singletonList(A));
    }
}
