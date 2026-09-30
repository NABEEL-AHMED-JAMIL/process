package process.forms;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
import process.security.TenantContext;
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Wave 5 Forms (lite) end to end on etl_job as the changelog builds it (V192), as process_app under row-level security
 * with the member signed in: the real form store, the real enqueuer (ProducerBulkEngine, BulkAction,
 * TransactionServiceImpl, Hibernate); only the inbox and the bucket are fakes. A submission to a form that names a job
 * queues exactly one run with the submission's file on it (input_bucket, input_key) -- what a step pipeline's Read
 * CSV/JSON/Parquet reads when it names no file -- and a busy job keeps the submission, RunNotStarted. Another
 * workspace's session sees none of it, even by raw SQL. Opt-in on NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class FormsPostgresTest {

    static final long A = 4697L;
    static final long B = 4698L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;
    private JdbcTemplate app;
    private FormSubmissionServiceTest.RecordingBucket bucket;
    private FormService forms;
    private FormSubmissionService submissions;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("forms_lite");
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
        this.app = new JdbcTemplate(db.appPool());
        this.sql.update("DELETE FROM form_submission");
        this.sql.update("DELETE FROM form_definition");
        this.sql.update("DELETE FROM job_audit_logs");
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9601 AND 9699");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9601 AND 9699");
        for (long tenant : new long[] {A, B}) {
            this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?) ON CONFLICT DO NOTHING",
                tenant, "FORMS" + tenant, "Forms " + tenant);
        }
        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        ProducerBulkEngine engine = new ProducerBulkEngine(new BulkAction(store, mock(NotificationPort.class)), store, mock(JobMail.class),
            null, null, jpa.transactionManager());
        WorkspaceDirectory workspaces = mock(WorkspaceDirectory.class);
        when(workspaces.pauseOf(anyLong())).thenReturn(Optional.empty());
        engine.useWorkspaceDirectory(workspaces);
        JdbcFormStore forms = new JdbcFormStore(this.app);
        this.bucket = new FormSubmissionServiceTest.RecordingBucket();
        this.forms = new FormService(forms, store);
        this.submissions = new FormSubmissionService(forms, this.forms, () -> FormInbox.Location.at("clinic-inbox"), this.bucket, engine,
            store, jpa.transactionManager());
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private void job(long jobId, long tenant) {
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority, created_by) "
            + "VALUES (?, ?, ?, 'Manual', ?, 'Active', 1, 61)", tenant, jobId, Timestamp.valueOf(LocalDateTime.now().minusDays(1)),
            "forms-" + jobId);
        this.sql.update("UPDATE source_job SET complete_job = false, fail_job = false, skip_job = false WHERE job_id = ?", jobId);
    }

    private long woundForm(long tenant, Long jobId) {
        TenantContext.set(tenant, "TENANT_ADMIN", 61L, "admin@clinic.example");
        ResponseDto saved = this.forms.save(new FormSaveRequest(null, "Wound intake", "Bedside assessment", FormStore.ACTIVE,
            FormFieldsTest.woundIntake(), jobId));
        assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo(SUCCESS);
        @SuppressWarnings("unchecked")
        long formId = (Long) ((Map<String, Object>) saved.getData()).get("formId");
        return formId;
    }

    private ResponseDto submitAsMember(long tenant, long formId) {
        TenantContext.set(tenant, "TENANT_USER", 62L, "nora@clinic.example");
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("patient_id", "P-00017");
        answers.put("wound_location", "Heel");
        answers.put("length_cm", "2.5");
        answers.put("observed_on", "2026-10-01");
        answers.put("infection_signs", "false");
        return this.submissions.submit(new FormSubmitRequest(formId, answers));
    }

    private List<Map<String, Object>> runsOf(long jobId) {
        return this.sql.queryForList("SELECT job_queue_id, job_status, run_manual, input_bucket, input_key, tenant_id FROM job_queue "
            + "WHERE job_id = ? ORDER BY job_queue_id", jobId);
    }

    @Test
    void aSubmissionQueuesOneRunWithItsFileOnTheRun() {
        job(9601, A);
        long formId = woundForm(A, 9601L);

        ResponseDto answer = submitAsMember(A, formId);

        assertThat(answer.getStatus()).isEqualTo(SUCCESS);
        List<Map<String, Object>> runs = runsOf(9601);
        assertThat(runs).hasSize(1);
        Map<String, Object> submission = this.sql.queryForMap("SELECT * FROM form_submission WHERE form_id = ?", formId);
        assertThat(submission).containsEntry("status", "RunStarted").containsEntry("job_queue_id", runs.get(0).get("job_queue_id"))
            .containsEntry("bucket", "clinic-inbox").containsEntry("tenant_id", A).containsEntry("submitted_by", 62L)
            .containsEntry("form_version", 1);
        assertThat(runs.get(0)).containsEntry("job_status", "Queue").containsEntry("run_manual", false)
            .containsEntry("input_bucket", "clinic-inbox").containsEntry("input_key", submission.get("storage_key"))
            .containsEntry("tenant_id", A);
        assertThat((String) submission.get("storage_key")).startsWith("intake/forms/form-" + formId + "/")
            .endsWith("/submission-" + submission.get("submission_id") + ".json");
        assertThat(this.bucket.uploads).containsExactly(A + " clinic-inbox " + submission.get("storage_key") + " application/json");
        assertThat(this.sql.queryForObject("SELECT answers->>'wound_location' FROM form_submission WHERE form_id = ?", String.class, formId))
            .isEqualTo("Heel");
        assertThat(this.sql.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ?", String.class,
            runs.get(0).get("job_queue_id"))).anySatisfy(line -> assertThat(line).contains("Wound intake").contains("submission"));
    }

    @Test
    void aSecondSubmissionWhileTheRunIsInFlightIsKeptButStartsNothing() {
        job(9611, A);
        long formId = woundForm(A, 9611L);

        submitAsMember(A, formId);
        ResponseDto second = submitAsMember(A, formId);

        assertThat(second.getMessage()).contains("did not start its run").contains("busy");
        assertThat(runsOf(9611)).hasSize(1);
        assertThat(this.sql.queryForList("SELECT status FROM form_submission WHERE form_id = ? ORDER BY submission_id", String.class,
            formId)).containsExactly("RunStarted", "RunNotStarted");
    }

    @Test
    void anotherWorkspaceSeesNoneOfItNotEvenByRawSqlUnderRowSecurity() {
        job(9621, A);
        long formId = woundForm(A, 9621L);
        submitAsMember(A, formId);

        TenantContext.set(B, "TENANT_ADMIN", 71L, "bella@bravo.example");
        assertThat(this.app.queryForObject("SELECT count(*) FROM form_definition", Long.class)).isZero();
        assertThat(this.app.queryForObject("SELECT count(*) FROM form_submission", Long.class)).isZero();
        assertThat(this.forms.fetch(formId).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(this.submissions.list(formId, 10).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        // B may not link A's job either.
        assertThat(this.forms.save(new FormSaveRequest(null, "Bravo form", null, null, Arrays.asList(new FormField("q", "Q", "text",
            false, null, null)), 9621L)).getMessage()).isEqualTo("The job this form starts was not found in this workspace.");

        TenantContext.set(A, "TENANT_USER", 62L, "nora@clinic.example");
        assertThat(this.app.queryForObject("SELECT count(*) FROM form_submission", Long.class)).isEqualTo(1L);
    }

    // ---- MIG-277 -----------------------------------------------------------------------------------------------

    @Test
    void everySaveKeepsItsVersionsFieldsAndASubmissionIsShownWithItsOwn() {
        long formId = woundForm(A, null);
        submitAsMember(A, formId);
        TenantContext.set(A, "TENANT_ADMIN", 61L, "admin@clinic.example");
        List<FormField> fewer = new ArrayList<>(FormFieldsTest.woundIntake());
        fewer.remove(6);
        assertThat(this.forms.save(new FormSaveRequest(formId, "Wound intake", null, FormStore.ACTIVE, fewer, null)).getStatus())
            .isEqualTo(SUCCESS);

        assertThat(this.sql.queryForList("SELECT version FROM form_version WHERE form_id = ? ORDER BY version", Integer.class, formId))
            .containsExactly(1, 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) this.submissions.list(formId, 10).getData();
        assertThat((List<?>) rows.get(0).get("fields")).as("version 1 had seven fields").hasSize(7);
        assertThat(new JdbcFormStore(this.app).fieldsAt(A, formId, 2).get()).hasSize(6);
    }

    @Test
    void aLookupReadsDistinctAnswersNewestFirstAndAnUploadIsTakenOnceUnderRowSecurity() {
        long formId = woundForm(A, null);
        submitAsMember(A, formId);
        submitAsMember(A, formId);
        TenantContext.set(A, "TENANT_USER", 62L, "nora@clinic.example");
        JdbcFormStore store = new JdbcFormStore(this.app);
        assertThat(store.answerValues(A, formId, "patient_id", 10)).containsExactly("P-00017");
        assertThat(store.answerValues(A, formId, "length_cm", 10)).containsExactly("2.5");

        long upload = store.createUpload(A, formId, "patient_id", 62L, "a.png", "image/png", 10, "wound-inbox", "intake/x.png");
        assertThat(store.openUpload(A, formId, "patient_id", 62L, upload)).isPresent();
        assertThat(store.openUpload(A, formId, "patient_id", 63L, upload)).as("someone else's").isEmpty();
        long submission = this.sql.queryForObject("SELECT max(submission_id) FROM form_submission WHERE form_id = ?", Long.class, formId);
        store.claimUploads(A, submission, Collections.singletonList(upload));
        assertThat(store.openUpload(A, formId, "patient_id", 62L, upload)).as("taken").isEmpty();
        assertThatThrownBy(() -> store.claimUploads(A, submission, Collections.singletonList(upload)))
            .isInstanceOf(IllegalStateException.class);

        TenantContext.set(B, "TENANT_ADMIN", 71L, "bella@bravo.example");
        assertThat(this.app.queryForObject("SELECT count(*) FROM form_upload", Long.class)).isZero();
        assertThat(this.app.queryForObject("SELECT count(*) FROM form_version", Long.class)).isZero();
    }
}
