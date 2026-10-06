package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.OneRunInFlight;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.BucketStore;
import process.security.TenantContext;
import process.util.BusinessTime;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Wave 5 Forms (lite): a submission is checked and stored, and -- when its form names a job -- written to the workspace's
 * inbox bucket as JSON and the job queued with that file as its input. With fakes: the store in memory, the inbox and the
 * bucket as recorders, the engine and the jobs as mocks. A busy job, a paused workspace, a missing inbox or a race lost to
 * the one-in-flight index never lose the submission: it is kept, RunNotStarted, with the reason.
 */
class FormSubmissionServiceTest {

    static final long A = 2924L;
    static final long B = 2925L;
    static final long JOB = 8801L;
    static final long RUN = 99001L;

    private final InMemoryFormStore store = new InMemoryFormStore();
    private final ProducerBulkEngine engine = mock(ProducerBulkEngine.class);
    private final TransactionServiceImpl jobs = mock(TransactionServiceImpl.class);
    private final RecordingBucket bucket = new RecordingBucket();
    private FormInbox.Location inbox = FormInbox.Location.at("wound-inbox");
    private FormService forms;
    private FormSubmissionService service;
    private SourceJob job;

    static final class RecordingBucket implements BucketStore {
        final List<String> uploads = new ArrayList<>();
        byte[] last;
        String unavailable;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public Listing list(long tenantId, String bucket, String prefix, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] read(long tenantId, String bucket, String key, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        /** MIG-279: the dataset rows, kept apart from the files a run is given. */
        final List<String> rows = new ArrayList<>();
        byte[] lastRow;

        @Override
        public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            if (key.startsWith("datasets/")) {
                this.rows.add(tenantId + " " + bucket + " " + key + " " + contentType);
                this.lastRow = content;
                return;
            }
            this.uploads.add(tenantId + " " + bucket + " " + key + " " + contentType);
            this.last = content;
        }
    }

    @BeforeEach
    void wire() {
        this.forms = new FormService(this.store, this.jobs);
        this.service = new FormSubmissionService(this.store, this.forms, () -> this.inbox, this.bucket, this.engine, this.jobs,
            mock(PlatformTransactionManager.class));
        this.job = new SourceJob();
        this.job.setJobId(JOB);
        this.job.setTenantId(A);
        this.job.setJobName("Wound triage pipeline");
        this.job.setJobStatus(Status.Active);
        this.job.setJobRunningStatus(JobStatus.Completed);
        when(this.jobs.findByJobId(JOB)).thenReturn(Optional.of(this.job));
        when(this.engine.workspacePause(anyLong())).thenReturn(Optional.empty());
        JobQueue run = new JobQueue();
        run.setJobQueueId(RUN);
        when(this.engine.addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong())).thenReturn(run);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private long form(long tenant, String status, Long jobId) {
        return this.store.create(tenant, "Wound intake " + tenant, "Bedside wound assessment", status,
            FormFields.valid(FormFieldsTest.woundIntake()), jobId, 1L);
    }

    private static Map<String, Object> goodAnswers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("patient_id", "P-00017");
        answers.put("wound_location", "Sacrum");
        answers.put("length_cm", 3.5);
        answers.put("observed_on", "2026-10-01");
        answers.put("infection_signs", true);
        return answers;
    }

    private static void as(long tenant, String role) {
        TenantContext.set(tenant, role, 4537L, "nora@clinic.example");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseDto answer) {
        return (Map<String, Object>) answer.getData();
    }

    // ---- no job ------------------------------------------------------------------------------------------------

    @Test
    void aFormWithoutAJobKeepsTheSubmissionAsReceived() {
        long formId = form(A, FormStore.ACTIVE, null);
        as(A, "TENANT_USER");

        ResponseDto answer = this.service.submit(new FormSubmitRequest(formId, goodAnswers()));

        assertThat(answer.getStatus()).isEqualTo(SUCCESS);
        assertThat(data(answer)).containsEntry("status", FormStore.RECEIVED).containsEntry("submittedByName", "nora@clinic.example");
        assertThat(this.store.submissions).hasSize(1);
        assertThat(this.bucket.uploads).isEmpty();
        verify(this.engine, never()).addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong());
    }

    // ---- the run -----------------------------------------------------------------------------------------------

    @Test
    void aSubmissionIsWrittenToTheInboxAsJsonAndStartsTheJobWithThatFile() throws Exception {
        long formId = form(A, FormStore.ACTIVE, JOB);
        as(A, "TENANT_USER");

        ResponseDto answer = this.service.submit(new FormSubmitRequest(formId, goodAnswers()));

        assertThat(answer.getStatus()).isEqualTo(SUCCESS);
        assertThat(answer.getMessage()).contains("started run #" + RUN);
        FormStore.Submission kept = this.store.submissions.values().iterator().next();
        String key = FormSubmissionService.keyOf(this.store.find(A, formId).get(), kept, BusinessTime.today());
        assertThat(key).startsWith("intake/forms/form-" + formId + "/").endsWith("/submission-" + kept.submissionId + ".json");
        assertThat(this.bucket.uploads).containsExactly(A + " wound-inbox " + key + " application/json");
        verify(this.engine).addFormJobInQueue(eq(this.job), eq("wound-inbox"), eq(key), eq("Wound intake " + A), eq(kept.submissionId));
        assertThat(kept.status).isEqualTo(FormStore.RUN_STARTED);
        assertThat(kept.jobQueueId).isEqualTo(RUN);
        assertThat(kept.bucket).isEqualTo("wound-inbox");
        assertThat(kept.storageKey).isEqualTo(key);

        JsonNode file = new ObjectMapper().readTree(new String(this.bucket.last, StandardCharsets.UTF_8));
        assertThat(file.isObject()).as("one object: Read CSV/JSON/Parquet reads it as one row").isTrue();
        List<String> columns = new ArrayList<>();
        file.fieldNames().forEachRemaining(columns::add);
        assertThat(columns).containsExactly("submission_id", "form_id", "form_name", "form_version", "submitted_by", "submitted_at",
            "patient_id", "wound_location", "length_cm", "observed_on", "infection_signs");
        assertThat(file.get("submitted_at").asText()).isEqualTo("2026-10-02T10:04:05-05:00");
        assertThat(file.get("length_cm").decimalValue()).isEqualByComparingTo("3.5");
        assertThat(file.get("infection_signs").asBoolean()).isTrue();
    }

    @Test
    void aBusyJobDoesNotStartAgainButTheSubmissionIsKeptWithTheReason() {
        this.job.setJobRunningStatus(JobStatus.Running);
        long formId = form(A, FormStore.ACTIVE, JOB);
        as(A, "TENANT_USER");

        ResponseDto answer = this.service.submit(new FormSubmitRequest(formId, goodAnswers()));

        assertThat(answer.getStatus()).as("the submission itself was taken").isEqualTo(SUCCESS);
        assertThat(answer.getMessage()).contains("did not start its run").contains("busy");
        FormStore.Submission kept = this.store.submissions.values().iterator().next();
        assertThat(kept.status).isEqualTo(FormStore.RUN_NOT_STARTED);
        assertThat(kept.reason).contains("still in flight ('Running')");
        assertThat(kept.answers).containsEntry("patient_id", "P-00017");
        assertThat(this.bucket.uploads).as("nothing written for a run that cannot start").isEmpty();
        verify(this.engine, never()).addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void aRaceLostToTheOneInFlightIndexIsBusyTooAndNamesTheFileItWrote() {
        when(this.engine.addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong())).thenThrow(
            new DataIntegrityViolationException("insert", new SQLException("duplicate key value violates unique constraint \""
                + OneRunInFlight.INDEX + "\"", "23505")));
        long formId = form(A, FormStore.ACTIVE, JOB);
        as(A, "TENANT_USER");

        this.service.submit(new FormSubmitRequest(formId, goodAnswers()));

        FormStore.Submission kept = this.store.submissions.values().iterator().next();
        assertThat(kept.status).isEqualTo(FormStore.RUN_NOT_STARTED);
        assertThat(kept.reason).contains("busy");
        assertThat(kept.jobQueueId).isNull();
        assertThat(kept.bucket).isEqualTo("wound-inbox");
        assertThat(kept.storageKey).startsWith("intake/forms/");
    }

    @Test
    void anInactiveJobAPausedWorkspaceNoInboxAndNoBucketAccessEachSayWhy() {
        long formId = form(A, FormStore.ACTIVE, JOB);
        as(A, "TENANT_USER");

        this.job.setJobStatus(Status.Inactive);
        assertThat(this.reasonOf(formId)).isEqualTo("The job this form starts is not active (Inactive).");
        this.job.setJobStatus(Status.Active);

        when(this.engine.workspacePause(A)).thenReturn(Optional.of("Suspended"));
        assertThat(this.reasonOf(formId)).isEqualTo("This workspace is Suspended, so its runs are paused.");
        when(this.engine.workspacePause(A)).thenReturn(Optional.empty());

        this.bucket.unavailable = "storage-service does not accept Core's pipeline caller";
        assertThat(this.reasonOf(formId)).contains("Pipelines cannot read the workspace's buckets yet");
        this.bucket.unavailable = null;

        this.inbox = FormInbox.Location.none(StorageFormInbox.NO_INBOX);
        assertThat(this.reasonOf(formId)).isEqualTo(StorageFormInbox.NO_INBOX);

        assertThat(this.bucket.uploads).isEmpty();
        verify(this.engine, never()).addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong());
        assertThat(this.store.submissions).as("every submission kept").hasSize(4);
    }

    @Test
    void aJobOfAnotherWorkspaceIsNeverStartedEvenIfTheFormNamesIt() {
        this.job.setTenantId(B);
        long formId = form(A, FormStore.ACTIVE, JOB);
        as(A, "TENANT_ADMIN");

        assertThat(this.reasonOf(formId)).isEqualTo("The job this form starts no longer exists.");
        verify(this.engine, never()).addFormJobInQueue(any(), anyString(), anyString(), anyString(), anyLong());
    }

    private String reasonOf(long formId) {
        ResponseDto answer = this.service.submit(new FormSubmitRequest(formId, goodAnswers()));
        long last = (Long) data(answer).get("submissionId");
        return this.store.submissions.get(last).reason;
    }

    // ---- refusals ----------------------------------------------------------------------------------------------

    @Test
    void wrongAnswersAreRefusedByFieldAndNothingIsStored() {
        long formId = form(A, FormStore.ACTIVE, JOB);
        as(A, "TENANT_USER");
        Map<String, Object> answers = goodAnswers();
        answers.remove("patient_id");
        answers.put("length_cm", "long");

        ResponseDto answer = this.service.submit(new FormSubmitRequest(formId, answers));

        assertThat(answer.getStatus()).isEqualTo(ERROR);
        assertThat(answer.getMessage()).contains("2 answers need attention");
        assertThat(data(answer).get("problems").toString()).contains("patient_id=Patient ID is required.")
            .contains("length_cm=Length (cm): enter a number.");
        assertThat(this.store.submissions).isEmpty();
        assertThat(this.bucket.uploads).isEmpty();
    }

    @Test
    void aDraftOrArchivedFormTakesNoSubmissionAndReadsAsMissingToAMember() {
        long draft = form(A, FormStore.DRAFT, null);
        as(A, "TENANT_USER");
        assertThat(this.service.submit(new FormSubmitRequest(draft, goodAnswers())).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        as(A, "TENANT_ADMIN");
        assertThat(this.service.submit(new FormSubmitRequest(draft, goodAnswers())).getMessage()).startsWith(FormSubmissionService.CLOSED);
        assertThat(this.store.submissions).isEmpty();
    }

    @Test
    void anotherWorkspacesFormIsNotFoundAndNothingOfItsIsTouched() {
        long theirs = form(B, FormStore.ACTIVE, JOB);
        as(A, "TENANT_ADMIN");

        assertThat(this.service.submit(new FormSubmitRequest(theirs, goodAnswers())).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(this.service.list(theirs, 50).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(this.service.export(theirs)).isNull();
        as(B, "TENANT_ADMIN");
        long submission = (Long) data(this.service.submit(new FormSubmitRequest(theirs, goodAnswers()))).get("submissionId");
        as(A, "TENANT_ADMIN");
        assertThat(this.service.fetch(submission).getMessage()).isEqualTo(FormSubmissionService.SUBMISSION_NOT_FOUND);
    }

    @Test
    void aCallerWithNoWorkspaceIsToldSo() {
        long formId = form(A, FormStore.ACTIVE, null);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root");
        assertThat(this.service.submit(new FormSubmitRequest(formId, goodAnswers())).getMessage()).isEqualTo(FormService.NO_WORKSPACE);
        TenantContext.set(0L, "TENANT_ADMIN", 1L, "nobody");
        assertThat(this.service.list(formId, 10).getMessage()).isEqualTo(FormService.NO_WORKSPACE);
    }

    // ---- reading -----------------------------------------------------------------------------------------------

    @Test
    void theExportIsCsvWithTheFieldsLabelsAndDefusesFormulas() {
        long formId = form(A, FormStore.ACTIVE, null);
        as(A, "TENANT_USER");
        Map<String, Object> answers = goodAnswers();
        answers.put("notes", "=HYPERLINK(\"http://evil\"), and a comma");
        this.service.submit(new FormSubmitRequest(formId, answers));

        FormSubmissionService.Export export = this.service.export(formId);

        String csv = new String(export.content, StandardCharsets.UTF_8);
        assertThat(export.fileName).matches("wound-intake-2924-submissions-\\d{4}-\\d{2}-\\d{2}\\.csv");
        assertThat(csv).startsWith("﻿Submission,Submitted at,Submitted by,Status,Run,Reason,Approval,Patient ID,Wound location,Length (cm),"
            + "Observed on,Signs of infection,Notes,Nurse e-mail\r\n");
        assertThat(csv).contains(",2026-10-02 10:04:05,nora@clinic.example,Received,,,,P-00017,Sacrum,3.5,2026-10-01,Yes,"
            + "\"'=HYPERLINK(\"\"http://evil\"\"), and a comma\",\r\n");
        assertThat(FormSubmissionService.cell("-12.5")).isEqualTo("-12.5");
        assertThat(FormSubmissionService.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void aFormsSubmissionsAreListedNewestFirstForItsWorkspace() {
        long formId = form(A, FormStore.ACTIVE, null);
        as(A, "TENANT_USER");
        this.service.submit(new FormSubmitRequest(formId, goodAnswers()));
        this.service.submit(new FormSubmitRequest(formId, goodAnswers()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) this.service.list(formId, 0).getData();

        assertThat(rows).hasSize(2);
        assertThat((Long) rows.get(0).get("submissionId")).isGreaterThan((Long) rows.get(1).get("submissionId"));
        assertThat(rows.get(0)).containsKeys("answers", "status", "jobQueueId", "reason", "submittedAt");
        assertThat(this.service.fetch((Long) rows.get(0).get("submissionId")).getStatus()).isEqualTo(SUCCESS);
    }
}
