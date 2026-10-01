package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static process.util.ProcessUtil.SUCCESS;

/**
 * MIG-279: a form names a workflow -- checked when it is saved -- and each submission starts a request of it; the
 * submission's status follows the request (Pending, Overdue, Approved ...) as its events arrive. Every submission is a
 * row of the form's Analytics dataset, written again when its status changes; an Active form registers that dataset.
 */
class FormWorkflowDatasetTest {

    static final long A = 2924L;

    /** workflow-service as a recorder: one Active, published workflow ("approve-visit"), one Inactive. */
    static final class FakeWorkflows implements FormWorkflows {
        final List<String> started = new ArrayList<>();
        Map<String, Object> lastSubject;
        RuntimeException startFails;

        @Override
        public Optional<Workflow> find(long tenantId, String key) {
            if ("approve-visit".equals(key)) {
                return Optional.of(new Workflow(key, "Approve a visit", "Active", 2));
            }
            if ("old-one".equals(key)) {
                return Optional.of(new Workflow(key, "Old one", "Inactive", 1));
            }
            return Optional.empty();
        }

        @Override
        public Started start(long tenantId, String key, long submissionId, String title, Map<String, Object> subject, Long requestedBy) {
            if (this.startFails != null) {
                throw this.startFails;
            }
            this.started.add(tenantId + " " + key + " " + FormWorkflows.subjectOf(submissionId) + " " + title + " by " + requestedBy);
            this.lastSubject = subject;
            return new Started(3000 + submissionId, "Running");
        }
    }

    private final InMemoryFormStore store = new InMemoryFormStore();
    private final FormSubmissionServiceTest.RecordingBucket bucket = new FormSubmissionServiceTest.RecordingBucket();
    private final FakeWorkflows workflows = new FakeWorkflows();
    private final List<String> registered = new ArrayList<>();
    private FormService forms;
    private FormSubmissionService service;

    @BeforeEach
    void wire() {
        FormInbox inbox = () -> FormInbox.Location.at("wound-inbox");
        FormDatasets.Registry registry = (alias, path, name) -> {
            this.registered.add(alias + " " + path + " " + name);
            return 77L;
        };
        FormDatasetWriter rows = new FormDatasetWriter(this.store, this.bucket);
        this.forms = new FormService(this.store, mock(TransactionServiceImpl.class), this.workflows, inbox, registry, rows);
        this.service = new FormSubmissionService(this.store, this.forms, inbox, this.bucket, mock(ProducerBulkEngine.class),
            mock(TransactionServiceImpl.class), mock(PlatformTransactionManager.class), this.workflows, rows);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private ResponseDto save(String workflowKey) {
        TenantContext.set(A, "TENANT_ADMIN", 4537L, "admin@clinic.example");
        FormSaveRequest request = new FormSaveRequest(null, "Visit", null, FormStore.ACTIVE,
            Collections.singletonList(new FormField("patient_id", "Patient ID", "text", true, null, null)), null);
        request.setWorkflowKey(workflowKey);
        return this.forms.save(request);
    }

    @SuppressWarnings("unchecked")
    private static long formIdOf(ResponseDto saved) {
        return ((Number) ((Map<String, Object>) saved.getData()).get("formId")).longValue();
    }

    @Test
    void aFormNamesOnlyAnActivePublishedWorkflowAndAnActiveFormIsADataset() {
        assertThat(save("nope").getMessage()).isEqualTo("The workflow 'nope' was not found in this workspace.");
        assertThat(save("old-one").getMessage()).contains("is Inactive");

        ResponseDto saved = save("approve-visit");
        assertThat(saved.getStatus()).isEqualTo(SUCCESS);
        assertThat(saved.getMessage()).contains("Its submissions are the Analytics dataset 'Form: Visit'.");
        long formId = formIdOf(saved);
        assertThat(this.registered).containsExactly("wound-inbox datasets/forms/form-" + formId + "/*.json Form: Visit");
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) saved.getData();
        assertThat(view).containsEntry("workflowKey", "approve-visit");
        assertThat(view.get("dataset").toString()).contains("analyticsDatasetId=77").contains("connection=wound-inbox");

        // Saving again registers nothing more.
        TenantContext.set(A, "TENANT_ADMIN", 4537L, "admin@clinic.example");
        FormSaveRequest again = new FormSaveRequest(formId, "Visit", null, FormStore.ACTIVE,
            Collections.singletonList(new FormField("patient_id", "Patient ID", "text", true, null, null)), null);
        again.setWorkflowKey("approve-visit");
        assertThat(this.forms.save(again).getStatus()).isEqualTo(SUCCESS);
        assertThat(this.registered).hasSize(1);
    }

    @Test
    void aSubmissionStartsItsRequestAndItsStatusFollowsTheRequestRowAndAll() throws Exception {
        long formId = formIdOf(save("approve-visit"));
        TenantContext.set(A, "TENANT_USER", 4597L, "alex@clinic.example");
        ResponseDto sent = this.service.submit(new FormSubmitRequest(formId, Collections.singletonMap("patient_id", "P-1")));

        assertThat(sent.getStatus()).isEqualTo(SUCCESS);
        FormStore.Submission kept = this.store.submissions.values().iterator().next();
        assertThat(sent.getMessage()).contains("Its approval (request #" + (3000 + kept.submissionId) + ") is pending.");
        assertThat(this.workflows.started).containsExactly(A + " approve-visit form-submission:" + kept.submissionId + " Visit #"
            + kept.submissionId + " by 4597");
        assertThat(this.workflows.lastSubject).containsEntry("patient_id", "P-1").containsEntry("form", "Visit");
        assertThat(kept.workflowStatus).isEqualTo("Pending");

        String rowKey = "datasets/forms/form-" + formId + "/submission-" + kept.submissionId + ".json";
        assertThat(this.bucket.rows).containsExactly(A + " wound-inbox " + rowKey + " application/json");
        assertThat(this.bucket.uploads).as("no job: no run file").isEmpty();
        JsonNode row = new ObjectMapper().readTree(new String(this.bucket.lastRow, StandardCharsets.UTF_8));
        assertThat(row.path("approval_status").asText()).isEqualTo("Pending");
        assertThat(row.path("patient_id").asText()).isEqualTo("P-1");

        // The request is approved: the submission and its row follow.
        assertThat(this.service.followWorkflow(A, kept.submissionId, 3000 + kept.submissionId, "Running", false, "Finance approval")).isTrue();
        assertThat(this.store.submissions.get(kept.submissionId).workflowStage).isEqualTo("Finance approval");
        assertThat(this.service.followWorkflow(A, kept.submissionId, 3000 + kept.submissionId, "Approved", false)).isTrue();
        assertThat(this.store.submissions.get(kept.submissionId).workflowStage).as("ended: no stage").isNull();
        assertThat(this.store.submissions.get(kept.submissionId).workflowStatus).isEqualTo("Approved");
        assertThat(this.bucket.rows).hasSize(3);
        assertThat(new ObjectMapper().readTree(new String(this.bucket.lastRow, StandardCharsets.UTF_8)).path("approval_status").asText())
            .isEqualTo("Approved");
        assertThat(this.service.followWorkflow(A + 1, kept.submissionId, 1L, "Rejected", false)).as("another workspace").isFalse();
    }

    @Test
    void aFormThatBecomesADatasetLaterBringsItsEarlierSubmissionsAsRows() {
        TenantContext.set(A, "TENANT_ADMIN", 4537L, "admin@clinic.example");
        FormSaveRequest draft = new FormSaveRequest(null, "Later", null, FormStore.DRAFT,
            Collections.singletonList(new FormField("patient_id", "Patient ID", "text", true, null, null)), null);
        long formId = formIdOf(this.forms.save(draft));
        this.store.receive(A, formId, 1, Collections.singletonMap("patient_id", "P-1"), null, 4597L, "alex");
        this.store.receive(A, formId, 1, Collections.singletonMap("patient_id", "P-2"), null, 4597L, "alex");
        assertThat(this.bucket.rows).isEmpty();

        ResponseDto activated = this.forms.status(new FormStatusRequest(formId, FormStore.ACTIVE));
        assertThat(activated.getMessage()).contains("Its submissions are the Analytics dataset 'Form: Later' (2 so far).");
        assertThat(this.bucket.rows).hasSize(2).allMatch(r -> r.contains("datasets/forms/form-" + formId + "/submission-"));
    }

    @Test
    void aRequestThatDoesNotStartLeavesTheSubmissionNotStartedWithWhy() {
        long formId = formIdOf(save("approve-visit"));
        this.workflows.startFails = new IllegalStateException("There is no active workflow \"approve-visit\".");
        TenantContext.set(A, "TENANT_USER", 4597L, "alex@clinic.example");
        ResponseDto sent = this.service.submit(new FormSubmitRequest(formId, Collections.singletonMap("patient_id", "P-1")));

        assertThat(sent.getStatus()).isEqualTo(SUCCESS);
        assertThat(sent.getMessage()).contains("Its approval did not start: There is no active workflow");
        FormStore.Submission kept = this.store.submissions.values().iterator().next();
        assertThat(kept.workflowStatus).isEqualTo(FormStore.NOT_STARTED);
        assertThat(kept.workflowReason).contains("no active workflow");
    }

    @Test
    void aRequestsStateIsASubmissionsStatus() {
        assertThat(FormSubmissionService.statusOf("Running", false)).isEqualTo("Pending");
        assertThat(FormSubmissionService.statusOf("Waiting", true)).isEqualTo("Overdue");
        assertThat(FormSubmissionService.statusOf("Rejected", true)).isEqualTo("Rejected");
        assertThat(FormSubmissionService.statusOf(null, false)).isEqualTo("Pending");
        assertThat(FormWorkflows.submissionOf("form-submission:41")).isEqualTo(41L);
        assertThat(FormWorkflows.submissionOf("purchase:41")).isNull();
    }

    @Test
    void theListenerTakesAFormSubmissionsChangeAndPassesOverAnyOther() {
        FormSubmissionService followed = mock(FormSubmissionService.class);
        FormWorkflowListener listener = new FormWorkflowListener(followed);
        listener.onChange("{\"eventType\":\"platform.workflow.instance-changed.v1\",\"tenantId\":2924,\"payload\":{\"instanceId\":3001,"
            + "\"tenantId\":2924,\"subjectId\":\"form-submission:41\",\"state\":\"Running\",\"overdue\":true}}");
        verify(followed).followWorkflow(2924L, 41L, 3001L, "Running", true, null);
        listener.onChange("{\"payload\":{\"instanceId\":3001,\"tenantId\":2924,\"subjectId\":\"form-submission:41\","
            + "\"state\":\"Running\",\"overdue\":false,\"stage\":\"Manager approval\"}}");
        verify(followed).followWorkflow(2924L, 41L, 3001L, "Running", false, "Manager approval");
        listener.onChange("{\"payload\":{\"instanceId\":3002,\"tenantId\":2924,\"subjectId\":\"purchase:7\",\"state\":\"Approved\"}}");
        listener.onChange("not json");
        verifyNoMoreInteractions(followed);
    }

    @Test
    void aRowFlattensTablesFilesAndSignatures() {
        FormStore.Submission s = new FormStore.Submission(5, 1, A, 2, rowAnswers(), 4597L, "alex", null, "Received", null, null, null, null,
            null);
        s.workflowStatus = "Approved";
        Map<String, Object> row = FormDatasets.rowOf(Collections.singletonList(new FormField("signed", "S", "signature", false, null, null)),
            s);
        assertThat(row).containsEntry("approval_status", "Approved").containsEntry("signed", "Signed").containsEntry("photo", "a.png")
            .containsEntry("doses", "[{\"drug\":\"A\"}]");
        assertThat(row.keySet()).startsWith("submission_id", "form_id", "form_version");
    }

    private static Map<String, Object> rowAnswers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("signed", Collections.singletonMap("uploadId", 1));
        answers.put("photo", Collections.singletonList(fileRef()));
        answers.put("doses", Collections.singletonList(Collections.singletonMap("drug", "A")));
        return answers;
    }

    private static Map<String, Object> fileRef() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("uploadId", 2);
        file.put("name", "a.png");
        return file;
    }
}
