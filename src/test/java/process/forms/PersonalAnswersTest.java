package process.forms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.ProducerBulkEngine;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ZT-I34 (owner 2026-10-27, "mask for readers without access"): Forms > Submissions and its CSV mask a form's answers marked
 * personal data for a member who may not see personal data -- the rule the requests follow: an administrator, a member
 * holding See personal data, and the person who sent it see them; when Identity cannot say, masked.
 */
class PersonalAnswersTest {

    static final long A = 2924L;
    static final long DESK = 4597L;
    static final long NURSE = 4598L;
    static final String MASK = PersonalAnswers.MASK;

    private final InMemoryFormStore store = new InMemoryFormStore();
    private final IdentityPort identity = mock(IdentityPort.class);
    private FormSubmissionService service;
    private long submissionId;
    private long formId;

    @BeforeEach
    void wire() throws Exception {
        FormInbox inbox = () -> FormInbox.Location.at("intake-inbox");
        FormDatasetWriter rows = new FormDatasetWriter(this.store, new FormSubmissionServiceTest.RecordingBucket());
        FormService forms = new FormService(this.store, mock(TransactionServiceImpl.class), new SubjectFieldsTest.Recording(), inbox,
            (alias, path, name) -> 77L, rows);
        this.service = new FormSubmissionService(this.store, forms, inbox, new FormSubmissionServiceTest.RecordingBucket(),
            mock(ProducerBulkEngine.class), mock(TransactionServiceImpl.class), mock(PlatformTransactionManager.class),
            new SubjectFieldsTest.Recording(), rows);
        PersonalAnswers personal = new PersonalAnswers();
        personal.useIdentity(this.identity);
        this.service.usePersonalAnswers(personal);

        TenantContext.set(A, "TENANT_ADMIN", 4537L, "admin@clinic.example");
        FormField name = SubjectFieldsTest.field("last_name", "Last name", "text", "Patient", true);
        FormField reason = SubjectFieldsTest.field("reason", "Reason for visit", "longText", "This visit", null);
        @SuppressWarnings("unchecked")
        long id = ((Number) ((Map<String, Object>) forms.save(new FormSaveRequest(null, "Intake", null, FormStore.ACTIVE,
            Arrays.asList(name, reason), null)).getData()).get("formId")).longValue();
        this.formId = id;
        TenantContext.set(A, "TENANT_USER", DESK, "desk@clinic.example");
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("last_name", "Example");
        answers.put("reason", "Chest pain");
        this.service.submit(new FormSubmitRequest(id, answers));
        this.submissionId = this.store.submissions.keySet().iterator().next();
        this.store.submissions.get(this.submissionId).workflowReason = "Seen Example: ECG on arrival.";
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private void as(String role, long user, boolean holds) {
        TenantContext.set(A, role, user, "u" + user + "@clinic.example");
        when(this.identity.pageDecision(any(), anyLong(), eq(PersonalAnswers.PATH))).thenReturn(new IdentityPort.PageDecision(holds, holds ? null
            : "See personal data is not part of your access."));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetched() {
        return (Map<String, Object>) this.service.fetch(this.submissionId).getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listed() {
        ResponseDto list = this.service.list(this.formId, 10);
        return ((List<Map<String, Object>>) list.getData()).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> answers(Map<String, Object> view) {
        return (Map<String, Object>) view.get("answers");
    }

    @Test
    void aMemberWithoutSeePersonalDataReadsThePersonalAnswersMaskedEverywhere() {
        this.as("TENANT_USER", NURSE, false);
        assertThat(answers(this.fetched())).containsEntry("last_name", MASK).containsEntry("reason", "Chest pain");
        assertThat(this.fetched()).containsEntry("personalMasked", true).containsEntry("workflowReason", "Seen " + MASK + ": ECG on arrival.");
        assertThat(answers(this.listed())).containsEntry("last_name", MASK);
        String csv = new String(this.service.export(this.formId).content, StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("Example").contains(MASK).contains("Chest pain");
    }

    @Test
    void anAdministratorAHolderAndItsSenderReadThemInFull() {
        this.as("TENANT_ADMIN", 4537L, false);
        assertThat(answers(this.fetched())).containsEntry("last_name", "Example");
        this.as("TENANT_USER", NURSE, true);
        assertThat(answers(this.fetched())).containsEntry("last_name", "Example");
        assertThat(this.fetched()).doesNotContainKey("personalMasked");
        this.as("TENANT_USER", DESK, false);
        assertThat(answers(this.listed())).containsEntry("last_name", "Example");
        assertThat(new String(this.service.export(this.formId).content, StandardCharsets.UTF_8)).contains("Example");
    }

    @Test
    void whenIdentityCannotSayTheyAreMasked() {
        TenantContext.set(A, "TENANT_USER", NURSE, "nurse@clinic.example");
        when(this.identity.pageDecision(any(), anyLong(), eq(PersonalAnswers.PATH))).thenThrow(new IdentityPort.Unavailable("down", null));
        assertThat(answers(this.fetched())).containsEntry("last_name", MASK);
    }
}
