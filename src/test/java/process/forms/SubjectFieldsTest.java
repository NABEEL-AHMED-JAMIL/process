package process.forms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ZT-I34 (owner 2026-10-27, "mask for readers without access"): a form's field can be personal data; a submission's request
 * carries what each key of its subject is -- the form's fields in its order, with their labels, sections and which are
 * personal -- so workflow-service shows the answers as the form asks them and masks the personal ones; a request started
 * before that asks Core once, at the internal door.
 */
class SubjectFieldsTest {

    static final long A = 2924L;

    static FormField field(String key, String label, String type, String section, Boolean personal) {
        FormField f = new FormField(key, label, type, false, null, null);
        f.setSection(section);
        f.setPersonal(personal);
        return f;
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    @Test
    void aFieldIsPersonalOnAnyTypeAndAbsentIsNo() {
        List<FormField> kept = FormFields.valid(Arrays.asList(field("first_name", "First name", "text", "Patient", true),
            field("dob", "Date of birth", "date", "Patient", Boolean.TRUE), field("pain", "Pain", "number", null, false),
            field("notes", "Notes", "longText", null, null)));
        assertThat(kept.stream().map(FormField::getPersonal).collect(Collectors.toList())).containsExactly(true, true, null, null);
    }

    @Test
    void theSubjectsFieldsAreTheFormsInItsOrderThenTheSubmissionsOwn() {
        List<Map<String, Object>> fields = SubjectFields.of(Arrays.asList(field("dob", "Date of birth", "date", "Patient", true),
            field("height_cm", "Height (cm)", "number", "This visit", null)), Collections.emptyList());
        assertThat(fields.stream().map(f -> f.get("key")).collect(Collectors.toList()))
            .containsExactly("dob", "height_cm", "form", "submission_id", "submitted_by");
        assertThat(fields.get(0)).containsEntry("label", "Date of birth").containsEntry("type", "date").containsEntry("section", "Patient")
            .containsEntry("personal", true);
        assertThat(fields.get(1)).containsEntry("label", "Height (cm)").doesNotContainKey("personal");
        assertThat(fields.get(4)).containsEntry("label", "Submitted by");
    }

    @Test
    void aFieldMarkedPersonalLaterIsPersonalInEarlierRequestsToo() {
        List<Map<String, Object>> fields = SubjectFields.of(Collections.singletonList(field("phone", "Phone", "text", null, null)),
            Collections.singletonList(field("phone", "Phone number", "text", null, true)));
        assertThat(fields.get(0)).containsEntry("label", "Phone").containsEntry("personal", true);
    }

    /** workflow-service as a recorder of what a start carries. */
    static final class Recording implements FormWorkflows {
        List<Map<String, Object>> subjectFields;

        @Override
        public Optional<Workflow> find(long tenantId, String key) {
            return Optional.of(new Workflow(key, "Intake review", "Active", 1));
        }

        @Override
        public Started start(long tenantId, String key, long submissionId, String title, Map<String, Object> subject, Long requestedBy) {
            throw new AssertionError("the start names the subject's fields");
        }

        @Override
        public Started start(long tenantId, String key, long submissionId, String title, Map<String, Object> subject, Long requestedBy,
            List<Map<String, Object>> subjectFields) {
            this.subjectFields = subjectFields;
            return new Started(3000 + submissionId, "Running");
        }
    }

    @Test
    void aSubmissionsRequestCarriesItsSubjectsFields() throws Exception {
        InMemoryFormStore store = new InMemoryFormStore();
        Recording workflows = new Recording();
        FormInbox inbox = () -> FormInbox.Location.at("intake-inbox");
        FormDatasetWriter rows = new FormDatasetWriter(store, new FormSubmissionServiceTest.RecordingBucket());
        FormService forms = new FormService(store, mock(TransactionServiceImpl.class), workflows, inbox, (alias, path, name) -> 77L, rows);
        FormSubmissionService service = new FormSubmissionService(store, forms, inbox, new FormSubmissionServiceTest.RecordingBucket(),
            mock(ProducerBulkEngine.class), mock(TransactionServiceImpl.class), mock(PlatformTransactionManager.class), workflows, rows);
        TenantContext.set(A, "TENANT_ADMIN", 4537L, "admin@clinic.example");
        FormSaveRequest request = new FormSaveRequest(null, "Intake", null, FormStore.ACTIVE, Arrays.asList(
            field("last_name", "Last name", "text", "Patient", true), field("reason", "Reason for visit", "longText", "This visit", null)), null);
        request.setWorkflowKey("intake-review");
        @SuppressWarnings("unchecked")
        long formId = ((Number) ((Map<String, Object>) forms.save(request).getData()).get("formId")).longValue();

        TenantContext.set(A, "TENANT_USER", 4597L, "desk@clinic.example");
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("last_name", "Example");
        answers.put("reason", "Chest pain");
        service.submit(new FormSubmitRequest(formId, answers));

        assertThat(workflows.subjectFields).extracting(f -> f.get("key")).containsExactly("last_name", "reason", "form", "submission_id",
            "submitted_by");
        assertThat(workflows.subjectFields.get(0)).containsEntry("personal", true).containsEntry("section", "Patient");
    }

    @Test
    void theInternalDoorAnswersASubmissionsFieldsWithTheTokenOnly() {
        InMemoryFormStore store = new InMemoryFormStore();
        long formId = store.create(A, "Intake", null, FormStore.ACTIVE, Collections.singletonList(field("dob", "Date of birth", "date", null,
            true)), null, 4537L);
        long submissionId = store.receive(A, formId, 1, Collections.singletonMap("dob", "1950-03-02"), null, 4597L, "desk@clinic.example")
            .submissionId;
        InternalFormSubjectRestApi api = new InternalFormSubjectRestApi(store, "secret-token");
        InternalFormSubjectRestApi.FieldsRequest asked = new InternalFormSubjectRestApi.FieldsRequest();
        asked.setTenantId(A);
        asked.setSubmissionId(submissionId);

        assertThat(api.subjectFields(null, asked).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.subjectFields("wrong", asked).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<?> answer = api.subjectFields("secret-token", asked);
        ResponseDto body = (ResponseDto) answer.getBody();
        assertThat(body.getStatus()).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) ((Map<String, Object>) body.getData()).get("fields");
        assertThat(fields.get(0)).containsEntry("key", "dob").containsEntry("personal", true);
        assertThat(TenantContext.getTenantId()).isNull();

        // Another workspace's submission is not there.
        asked.setTenantId(A + 1);
        assertThat(((ResponseDto) api.subjectFields("secret-token", asked).getBody()).getStatus()).isEqualTo("ERROR");
    }
}
