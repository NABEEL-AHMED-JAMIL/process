package process.documents;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.AcrossTenantsProxy;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.directory.WorkspaceDirectory;
import process.engine.BulkAction;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.pipeline.RunDocuments;
import process.pipeline.backing.Fakes;
import process.pipeline.tasks.ReadDocumentsSteps;
import process.security.TenantContext;
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-364, Core's half: Document Intelligence's approval (the committed fixture, document-approved-v1.json) through the
 * real listener, the real enqueuer (ProducerBulkEngine, BulkAction, TransactionServiceImpl, Hibernate) and a Postgres
 * built by the real changelog, as process_app under row security with nobody signed in -- the listener works as the
 * event's workspace only (RowSecurity.forTenant).
 *
 * Each job whose enabled trigger names the document's type gets its own run, once per document and version, the run
 * naming its documents (documentsOf); a redelivered event starts nothing more. A busy job or a paused workspace makes the
 * document wait, and the job's next run takes it (the batch size at a time); an inactive job records it Skipped with the
 * reason. Another workspace's jobs are never reached. And the read_documents watermark: only a completed run moves it,
 * and one workspace never sees another's. Opt-in on NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class DocumentTriggerPostgresTest {

    static final long A = 4697L;
    static final long B = 4698L;
    static final long TYPE = 1007L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;
    private WorkspaceDirectory workspaces;
    private DocumentTriggerService service;
    private DocumentApprovalListener listener;
    private DocumentQueueSweep sweep;
    private JdbcDocumentTriggerStore store;
    private final Fakes.Documents documents = new Fakes.Documents();

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("document_trigger");
        jpa = new ScratchJpa(db.appPool());
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

    @BeforeEach
    void rows() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM run_document_read");
        this.sql.update("DELETE FROM document_approval");
        this.sql.update("DELETE FROM job_document_trigger");
        this.sql.update("DELETE FROM job_audit_logs WHERE job_queue_id IN (SELECT job_queue_id FROM job_queue WHERE job_id BETWEEN 9601 AND 9699)");
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9601 AND 9699");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9601 AND 9699");
        for (long tenant : new long[] {A, B}) {
            this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?) ON CONFLICT DO NOTHING",
                tenant, "DOCS" + tenant, "Documents " + tenant);
        }
        TransactionServiceImpl jobs = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(jobs, mock(NotificationPort.class));
        ProducerBulkEngine engine = AcrossTenantsProxy.of(new ProducerBulkEngine(bulkAction, jobs, mock(JobMail.class), null, null,
            jpa.transactionManager()));
        this.workspaces = mock(WorkspaceDirectory.class);
        when(this.workspaces.pauseOf(anyLong())).thenReturn(Optional.empty());
        engine.useWorkspaceDirectory(this.workspaces);
        this.store = new JdbcDocumentTriggerStore(new JdbcTemplate(db.appPool()));
        this.service = new DocumentTriggerService(this.store, engine, jobs, jpa.transactionManager());
        this.service.useDocuments(this.documents);
        this.listener = new DocumentApprovalListener(this.service);
        this.sweep = new DocumentQueueSweep(db.appJdbc(), this.service);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private void job(long jobId, long tenant, String status) {
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority, created_by) "
            + "VALUES (?, ?, ?, 'Manual', ?, ?, 1, 61)", tenant, jobId, Timestamp.valueOf(LocalDateTime.now().minusDays(1)), "docs-" + jobId,
            status);
        this.sql.update("UPDATE source_job SET complete_job = false, fail_job = false, skip_job = false WHERE job_id = ?", jobId);
    }

    private void trigger(long jobId, long type, boolean enabled) {
        this.sql.update("INSERT INTO job_document_trigger (job_id, tenant_id, document_type_id, enabled) "
            + "SELECT job_id, tenant_id, ?, ? FROM source_job WHERE job_id = ?", type, enabled, jobId);
    }

    private List<Map<String, Object>> runsOf(long jobId) {
        return this.sql.queryForList("SELECT job_queue_id, job_status, run_manual, input_key, tenant_id FROM job_queue "
            + "WHERE job_id = ? ORDER BY job_queue_id", jobId);
    }

    private List<Map<String, Object>> approvalsOf(long jobId) {
        return this.sql.queryForList("SELECT extraction_id, type_version, tenant_id, outcome, reason, job_queue_id, approval FROM document_approval "
            + "WHERE job_id = ? ORDER BY document_approval_id", jobId);
    }

    /** The fixture's approval, in workspace {@code tenant}, of document {@code extraction} (version {@code version}). */
    private static String approval(long tenant, long extraction, int version) throws Exception {
        return DocumentApprovedContractTest.fixture().replace("\"tenantId\": 2961", "\"tenantId\": " + tenant)
            .replace("\"extractionId\": 1180", "\"extractionId\": " + extraction).replace("\"typeVersion\": 2", "\"typeVersion\": " + version);
    }

    @Test
    void anApprovalStartsEachJobOnItsTypeOnceAndTheRunNamesTheDocument() throws Exception {
        job(9601, A, "Active");
        trigger(9601, TYPE, true);
        job(9602, A, "Active");
        trigger(9602, TYPE, true);
        job(9603, A, "Active");
        trigger(9603, 2002L, true);
        job(9604, A, "Active");
        trigger(9604, TYPE, false);
        job(9605, B, "Active");
        trigger(9605, TYPE, true);

        this.listener.onApproval(approval(A, 1180, 2));

        for (long started : new long[] {9601, 9602}) {
            List<Map<String, Object>> runs = runsOf(started);
            assertThat(runs).as("job %s", started).hasSize(1);
            assertThat(runs.get(0)).containsEntry("job_status", "Queue").containsEntry("run_manual", false).containsEntry("input_key", null)
                .containsEntry("tenant_id", A);
            assertThat(approvalsOf(started)).hasSize(1).first().satisfies(a -> assertThat(a).containsEntry("extraction_id", 1180L)
                .containsEntry("type_version", 2).containsEntry("outcome", "Started").containsEntry("approval", "reviewer")
                .containsEntry("job_queue_id", runs.get(0).get("job_queue_id")).containsEntry("tenant_id", A));
            long run = (Long) runs.get(0).get("job_queue_id");
            List<RunDocuments.Document> named = RowSecurity.forTenant(A, () -> this.store.documentsOf(run));
            assertThat(named).extracting(d -> d.extractionId + " " + d.documentTypeId + " v" + d.typeVersion).containsExactly("1180 1007 v2");
            assertThat(this.sql.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ?", String.class, run))
                .anySatisfy(line -> assertThat(line).contains("document 1180").contains("approved"));
        }
        for (long untouched : new long[] {9603, 9604, 9605}) {
            assertThat(runsOf(untouched)).as("job %s", untouched).isEmpty();
            assertThat(approvalsOf(untouched)).as("job %s", untouched).isEmpty();
        }
    }

    /** Idempotent per document and version: the same approval delivered again starts nothing more; a new version does. */
    @Test
    void theSameApprovalDeliveredAgainStartsNothingMore() throws Exception {
        job(9611, A, "Active");
        trigger(9611, TYPE, true);

        this.listener.onApproval(approval(A, 1180, 2));
        // The run finished, so the in-flight rule is not what holds the second delivery back: the document and version are.
        finish((Long) runsOf(9611).get(0).get("job_queue_id"));
        this.listener.onApproval(approval(A, 1180, 2));
        this.listener.onApproval(approval(A, 1180, 2));
        assertThat(runsOf(9611)).hasSize(1);
        assertThat(approvalsOf(9611)).hasSize(1);

        // Delivered at once on eight threads: still one row and one run.
        job(9612, A, "Active");
        trigger(9612, TYPE, true);
        String message = approval(A, 1190, 1);
        atOnce(8, () -> this.listener.onApproval(message));
        assertThat(runsOf(9612)).hasSize(1);
        assertThat(approvalsOf(9612)).hasSize(1);
    }

    /** One workspace's approval never starts another workspace's job, even one triggered on the same type id. */
    @Test
    void anotherWorkspacesApprovalNeverStartsThisWorkspacesJobs() throws Exception {
        job(9621, A, "Active");
        trigger(9621, TYPE, true);
        job(9622, B, "Active");
        trigger(9622, TYPE, true);

        this.listener.onApproval(approval(B, 1200, 1));

        assertThat(runsOf(9621)).isEmpty();
        assertThat(approvalsOf(9621)).isEmpty();
        assertThat(runsOf(9622)).hasSize(1).first().satisfies(r -> assertThat(r.get("tenant_id")).isEqualTo(B));
        // Even asked directly, as B: A's trigger is not there to be seen.
        assertThat(RowSecurity.forTenant(B, () -> this.store.enabledFor(A, TYPE))).isEmpty();
        assertThat(RowSecurity.forTenant(B, () -> this.store.documentsOf((Long) runsOf(9622).get(0).get("job_queue_id")))).hasSize(1);
        assertThat(RowSecurity.forTenant(A, () -> this.store.documentsOf((Long) runsOf(9622).get(0).get("job_queue_id")))).isEmpty();
    }

    @Test
    void aBusyJobKeepsTheDocumentWaitingAndItsNextRunTakesThemABatchAtATime() throws Exception {
        job(9631, A, "Active");
        trigger(9631, TYPE, true);
        this.sql.update("UPDATE job_document_trigger SET batch_size = 10 WHERE job_id = 9631");
        busy(9631, 963101L);

        for (int i = 0; i < 25; i++) {
            this.listener.onApproval(approval(A, 2000 + i, 1));
        }
        assertThat(outcomes(9631)).containsEntry("Waiting", 25L);
        assertThat(approvalsOf(9631).get(0).get("reason").toString()).contains("in flight").contains("next run");
        assertThat(this.sweep.sweep()).as("in flight: they wait").isZero();

        int runs = drain(9631);

        assertThat(runs).isEqualTo(3);
        assertThat(outcomes(9631)).containsOnlyKeys("Started").containsEntry("Started", 25L);
        List<Long> made = this.sql.queryForList("SELECT job_queue_id FROM job_queue WHERE job_id = 9631 AND job_queue_id <> 963101 "
            + "ORDER BY job_queue_id", Long.class);
        assertThat(made).hasSize(3);
        List<Integer> sizes = new ArrayList<>();
        List<Long> order = new ArrayList<>();
        for (Long run : made) {
            List<RunDocuments.Document> docs = RowSecurity.forTenant(A, () -> this.store.documentsOf(run));
            sizes.add(docs.size());
            docs.forEach(d -> order.add(d.extractionId));
        }
        assertThat(sizes).containsExactly(10, 10, 5);
        assertThat(order).as("in the order they were approved").isSorted().hasSize(25);
    }

    @Test
    void anInactiveJobSkipsTheDocumentAndAPausedWorkspaceKeepsItWaiting() throws Exception {
        job(9641, A, "Inactive");
        trigger(9641, TYPE, true);
        job(9642, B, "Active");
        trigger(9642, TYPE, true);
        when(this.workspaces.pauseOf(B)).thenReturn(Optional.of(new WorkspaceDirectory.Pause(B, "Suspended", new Timestamp(0))));

        this.listener.onApproval(approval(A, 1180, 2));
        this.listener.onApproval(approval(B, 1300, 1));

        assertThat(runsOf(9641)).isEmpty();
        assertThat(approvalsOf(9641)).hasSize(1).first().satisfies(a -> {
            assertThat(a.get("outcome")).isEqualTo("Skipped");
            assertThat((String) a.get("reason")).contains("not active");
        });
        assertThat(runsOf(9642)).isEmpty();
        assertThat(approvalsOf(9642)).hasSize(1).first().satisfies(a -> assertThat(a.get("outcome")).isEqualTo("Waiting"));
        assertThat(this.sweep.sweep()).as("paused: the document waits").isZero();

        when(this.workspaces.pauseOf(B)).thenReturn(Optional.empty());
        assertThat(this.sweep.sweep()).isEqualTo(1);
        assertThat(runsOf(9642)).hasSize(1);
        assertThat(approvalsOf(9642)).first().satisfies(a -> assertThat(a.get("outcome")).isEqualTo("Started"));
    }

    @Test
    void documentsThatWaitedForAJobSwitchedOffSinceAreSkippedSayingSo() throws Exception {
        job(9645, A, "Active");
        trigger(9645, TYPE, true);
        busy(9645, 964501L);
        this.listener.onApproval(approval(A, 1180, 2));
        this.sql.update("UPDATE source_job SET job_status = 'Inactive' WHERE job_id = 9645");
        finish(964501L);

        assertThat(this.sweep.sweep()).isZero();

        assertThat(runsOf(9645)).hasSize(1);
        assertThat(approvalsOf(9645)).first().satisfies(a -> {
            assertThat(a.get("outcome")).isEqualTo("Skipped");
            assertThat((String) a.get("reason")).contains("not active");
        });
    }

    @Test
    void anUnreadableEventIsSkipped() throws Exception {
        job(9651, A, "Active");
        trigger(9651, TYPE, true);

        this.listener.onApproval("{\"eventType\":\"platform.document.approved.v1\"}");
        this.listener.onApproval("not json");

        assertThat(runsOf(9651)).isEmpty();
    }

    // ---- the console: sourceJob.json/documentTrigger and documentApprovals -------------------------------------------

    @Test
    void whoeverMaySeeAJobSetsItsTriggerOnATypeOfTheirWorkspace() throws Exception {
        job(9661, A, "Active");
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");

        assertThat(this.service.save(9661L, null, true, null).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.save(9661L, TYPE, true, 51).getStatus()).isEqualTo("ERROR");
        this.documents.refusal = "No document type 1007 in this workspace.";
        assertThat(this.service.save(9661L, TYPE, true, null).getMessage()).contains("no document type 1007");
        this.documents.refusal = null;
        ResponseDto saved = this.service.save(9661L, TYPE, true, 5);
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getMessage()).contains("'AP invoice'").contains("up to 5");
        assertThat(this.documents.requests).last().satisfies(r -> {
            assertThat(r.tenantId).as("the type is checked as the job's workspace").isEqualTo(A);
            assertThat(r.jobQueueId).as("a look, not a run's read: not billed").isNull();
        });
        assertThat(this.sql.queryForMap("SELECT tenant_id, document_type_id, enabled, batch_size, created_by FROM job_document_trigger "
            + "WHERE job_id = 9661")).containsEntry("tenant_id", A).containsEntry("document_type_id", TYPE).containsEntry("enabled", true)
            .containsEntry("batch_size", 5).containsEntry("created_by", 60L);
        // Saved again without a type or batch size: both kept; switched off.
        this.service.save(9661L, null, false, null);
        assertThat(this.service.trigger(9661L).getData()).hasFieldOrPropertyWithValue("documentTypeId", TYPE)
            .hasFieldOrPropertyWithValue("batchSize", 5).hasFieldOrPropertyWithValue("enabled", false);
        this.service.save(9661L, null, true, null);

        TenantContext.clear();
        this.listener.onApproval(approval(A, 1180, 2));
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvals = (List<Map<String, Object>>) this.service.approvals(9661L, 20).getData();
        assertThat(approvals).hasSize(1).first().satisfies(a -> {
            assertThat(a).containsEntry("extractionId", 1180L).containsEntry("outcome", "Started").containsEntry("typeVersion", 2);
            assertThat(a.get("jobQueueId")).isNotNull();
            assertThat((String) a.get("dateCreated")).endsWith("Z");
        });

        // A tenant user who neither made the job nor is assigned it sees no job at all.
        TenantContext.set(A, "TENANT_USER", 77L, "other@acme.example");
        assertThat(this.service.trigger(9661L).getMessage()).isEqualTo(DocumentTriggerService.JOB_NOT_FOUND);
        assertThat(this.service.approvals(9661L, 20).getMessage()).isEqualTo(DocumentTriggerService.JOB_NOT_FOUND);
        // Another workspace's administrator: not found, and nothing changed.
        TenantContext.set(B, "TENANT_ADMIN", 70L, "admin@beta.example");
        assertThat(this.service.save(9661L, TYPE, false, null).getMessage()).isEqualTo(DocumentTriggerService.JOB_NOT_FOUND);
        assertThat(this.service.delete(9661L).getMessage()).isEqualTo(DocumentTriggerService.JOB_NOT_FOUND);
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");
        assertThat(this.service.delete(9661L).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.service.trigger(9661L).getData()).hasFieldOrPropertyWithValue("configured", false);
    }

    /** UX review 2026-10-13 (DI1): what an approved document starts -- the workspace's triggers on schedules the caller sees. */
    @Test
    void theWorkspacesTriggersAreListedByTypeForWhoeverMaySeeTheirSchedules() {
        job(9671, A, "Active");
        job(9672, A, "Inactive");
        job(9673, A, "Delete");
        job(9674, B, "Active");
        trigger(9671, TYPE, true);
        trigger(9672, 1003L, false);
        trigger(9673, TYPE, true);
        trigger(9674, TYPE, true);

        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) this.service.triggers().getData();
        assertThat(rows).extracting(r -> r.get("jobId")).as("by type, a deleted schedule and another workspace's left out")
            .containsExactly(9672L, 9671L);
        assertThat(rows.get(0)).containsEntry("jobName", "docs-9672").containsEntry("jobStatus", "Inactive")
            .containsEntry("documentTypeId", 1003L).containsEntry("enabled", false);

        // A tenant user who neither made nor is assigned those schedules sees none of them.
        TenantContext.set(A, "TENANT_USER", 77L, "other@acme.example");
        assertThat((List<?>) this.service.triggers().getData()).isEmpty();
        TenantContext.set(B, "TENANT_ADMIN", 70L, "admin@beta.example");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> others = (List<Map<String, Object>>) this.service.triggers().getData();
        assertThat(others).extracting(r -> r.get("jobId")).containsExactly(9674L);
    }

    // ---- the read_documents watermark (run_document_read) ---------------------------------------------------------------

    /** Only a completed run moves the mark; a failed one's documents are read again; another workspace sees none of it. */
    @Test
    void theWatermarkMovesOnlyWithACompletedRunAndStaysInItsWorkspace() throws Exception {
        job(9671, A, "Active");
        JdbcDocumentReadMarks marks = new JdbcDocumentReadMarks(db.appJdbc());
        ReadDocumentsSteps steps = new ReadDocumentsSteps(this.documents, marks);
        for (int i = 1; i <= 3; i++) {
            this.documents.approve(A, TYPE, "INV-" + i, "V-1", i, 0);
        }
        this.documents.approve(B, TYPE, "B-SECRET", "V-9", 9, 0);

        run(9671, 967101L);
        assertThat(RowSecurity.forTenant(A, () -> steps.sinceLastRun(A, 9671L, 967101L))).containsExactly("INV-1", "INV-2", "INV-3");
        finish(967101L);
        long fourth = this.documents.approve(A, TYPE, "INV-4", "V-1", 4, 0);

        run(9671, 967102L);
        assertThat(RowSecurity.forTenant(A, () -> steps.sinceLastRun(A, 9671L, 967102L))).containsExactly("INV-4");
        this.sql.update("UPDATE job_queue SET job_status = 'Failed' WHERE job_queue_id = 967102");

        run(9671, 967103L);
        assertThat(RowSecurity.forTenant(A, () -> steps.sinceLastRun(A, 9671L, 967103L))).as("the failed run moved nothing")
            .containsExactly("INV-4");
        finish(967103L);
        assertThat(RowSecurity.forTenant(A, () -> marks.watermark(9671L, "step", TYPE))).isEqualTo(fourth);
        assertThat(this.sql.queryForObject("SELECT tenant_id FROM run_document_read WHERE job_queue_id = 967103", Long.class)).isEqualTo(A);
        // As B, the job's marks are not there: B's own run would start from the beginning.
        assertThat(RowSecurity.forTenant(B, () -> marks.watermark(9671L, "step", TYPE))).isZero();
        assertThat(RowSecurity.forTenant(B, () -> db.appJdbc().queryForObject("SELECT count(*) FROM run_document_read", Long.class))).isZero();
    }

    private void run(long jobId, long runId) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (?, ?, 'Running', now(), now(), 'Active')", runId, jobId);
    }

    private void busy(long jobId, long runId) {
        run(jobId, runId);
        this.sql.update("UPDATE source_job SET job_running_status = 'Running' WHERE job_id = ?", jobId);
    }

    private void finish(long runId) {
        this.sql.update("UPDATE job_queue SET job_status = 'Completed', end_time = now() WHERE job_queue_id = ?", runId);
        this.sql.update("UPDATE source_job SET job_running_status = 'Completed' WHERE job_id = (SELECT job_id FROM job_queue "
            + "WHERE job_queue_id = ?)", runId);
    }

    private int drain(long jobId) {
        int made = 0;
        for (int pass = 0; pass < 100; pass++) {
            for (Long inFlight : this.sql.queryForList("SELECT job_queue_id FROM job_queue WHERE job_id = ? AND job_status IN "
                + "('Queue', 'Start', 'Running')", Long.class, jobId)) {
                finish(inFlight);
            }
            int started = this.sweep.sweep();
            if (started == 0) {
                return made;
            }
            made += started;
        }
        return made;
    }

    private Map<String, Long> outcomes(long jobId) {
        Map<String, Long> out = new TreeMap<>();
        this.sql.query("SELECT outcome, count(*) AS n FROM document_approval WHERE job_id = ? GROUP BY outcome", rs -> {
            out.put(rs.getString("outcome"), rs.getLong("n"));
        }, jobId);
        return out;
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void atOnce(int threads, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> sent = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                sent.add(pool.submit(() -> {
                    go.await();
                    call.run();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> one : sent) {
                one.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
