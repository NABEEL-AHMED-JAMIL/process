package process.forms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.security.TenantContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Wave 5 Forms (lite), the builder: a workspace administrator creates, edits, activates and archives forms; a member
 * reads only the Active ones, and never the job behind them. Another workspace's form, or job, is not found.
 */
class FormServiceTest {

    static final long A = 2924L;
    static final long B = 2925L;

    private final InMemoryFormStore store = new InMemoryFormStore();
    private final TransactionServiceImpl jobs = mock(TransactionServiceImpl.class);
    private final FormService service = new FormService(this.store, this.jobs);

    @BeforeEach
    void jobs() {
        when(this.jobs.findByJobId(8801L)).thenReturn(Optional.of(job(8801L, A, Status.Active, "Wound triage pipeline")));
        when(this.jobs.findByJobId(8802L)).thenReturn(Optional.of(job(8802L, B, Status.Active, "Bravo private job")));
        when(this.jobs.findByJobId(8803L)).thenReturn(Optional.of(job(8803L, A, Status.Delete, "Deleted job")));
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private static SourceJob job(long id, long tenant, Status status, String name) {
        SourceJob job = new SourceJob();
        job.setJobId(id);
        job.setTenantId(tenant);
        job.setJobStatus(status);
        job.setJobName(name);
        return job;
    }

    private static void as(long tenant, String role) {
        TenantContext.set(tenant, role, 4537L, "admin@clinic.example");
    }

    private static FormSaveRequest wound(Long formId, String status, Long jobId) {
        return new FormSaveRequest(formId, "Wound intake", "Bedside wound assessment", status, FormFieldsTest.woundIntake(), jobId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseDto answer) {
        return (Map<String, Object>) answer.getData();
    }

    @Test
    void anAdministratorCreatesAFormLinkedToAJobThenEditsItOneVersionOn() {
        as(A, "TENANT_ADMIN");

        ResponseDto created = this.service.save(wound(null, null, 8801L));

        assertThat(created.getStatus()).isEqualTo(SUCCESS);
        assertThat(data(created)).containsEntry("status", FormStore.DRAFT).containsEntry("version", 1).containsEntry("fieldCount", 7)
            .containsEntry("jobId", 8801L).containsEntry("jobName", "Wound triage pipeline").containsEntry("startsJob", true);
        long formId = (Long) data(created).get("formId");

        ResponseDto saved = this.service.save(new FormSaveRequest(formId, "Wound intake", null, FormStore.ACTIVE,
            FormFieldsTest.woundIntake().subList(0, 3), null));

        assertThat(saved.getMessage()).isEqualTo("Form saved.");
        assertThat(data(saved)).containsEntry("version", 2).containsEntry("fieldCount", 3).containsEntry("startsJob", false)
            .containsEntry("status", FormStore.ACTIVE);
    }

    @Test
    void theBuildersMistakesAreRefusedInASentence() {
        as(A, "TENANT_ADMIN");
        assertThat(this.service.save(new FormSaveRequest(null, "  ", null, null, null, null)).getMessage()).isEqualTo("Give the form a name.");
        assertThat(this.service.save(new FormSaveRequest(null, "X", null, "Live", null, null)).getMessage())
            .isEqualTo("A form's status is Draft, Active or Archived.");
        assertThat(this.service.save(new FormSaveRequest(null, "X", null, FormStore.ACTIVE, null, null)).getMessage())
            .contains("at least one field");
        assertThat(this.service.save(new FormSaveRequest(null, "X", null, null,
            Collections.singletonList(new FormField("Bad Key", "B", "text", false, null, null)), null)).getMessage()).contains("lower-case");
        assertThat(this.service.save(wound(null, null, 8802L)).getMessage()).as("another workspace's job")
            .isEqualTo("The job this form starts was not found in this workspace.");
        assertThat(this.service.save(wound(null, null, 8803L)).getMessage()).as("a deleted job")
            .isEqualTo("The job this form starts was not found in this workspace.");
        assertThat(this.service.save(wound(null, null, 9999L)).getStatus()).isEqualTo(ERROR);
        this.service.save(wound(null, null, null));
        assertThat(this.service.save(wound(null, null, null)).getMessage()).isEqualTo("This workspace already has a form named 'Wound intake'.");
        assertThat(this.store.forms).hasSize(1);
    }

    @Test
    void aMemberBuildsNothingAndSeesOnlyActiveFormsWithoutTheirJob() {
        as(A, "TENANT_ADMIN");
        long active = (Long) data(this.service.save(wound(null, FormStore.ACTIVE, 8801L))).get("formId");
        long draft = (Long) data(this.service.save(new FormSaveRequest(null, "Draft form", null, null, null, null))).get("formId");

        as(A, "TENANT_USER");
        assertThat(this.service.save(wound(null, null, null)).getMessage()).isEqualTo(FormService.ADMINS_ONLY);
        assertThat(this.service.status(new FormStatusRequest(active, FormStore.ARCHIVED)).getMessage()).isEqualTo(FormService.ADMINS_ONLY);
        assertThat(this.service.linkableJobs().getMessage()).isEqualTo(FormService.ADMINS_ONLY);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) this.service.list(true).getData();
        assertThat(listed).extracting(f -> f.get("formId")).containsExactly(active);
        assertThat(listed.get(0)).containsEntry("startsJob", true).doesNotContainKeys("jobId", "jobName", "submissions");
        assertThat(this.service.fetch(draft).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(data(this.service.fetch(active))).containsKey("fields").doesNotContainKey("jobName");
    }

    @Test
    void archivingKeepsTheFormForAdministratorsAndActivatingNeedsAField() {
        as(A, "TENANT_ADMIN");
        long formId = (Long) data(this.service.save(wound(null, FormStore.ACTIVE, null))).get("formId");
        long empty = (Long) data(this.service.save(new FormSaveRequest(null, "Empty", null, null, null, null))).get("formId");

        assertThat(this.service.status(new FormStatusRequest(formId, FormStore.ARCHIVED)).getMessage()).contains("archived");
        assertThat(this.service.list(false).getData().toString()).doesNotContain("Wound intake");
        assertThat(this.service.list(true).getData().toString()).contains("Wound intake");
        assertThat(this.service.status(new FormStatusRequest(empty, FormStore.ACTIVE)).getMessage()).contains("at least one field");
    }

    @Test
    void anotherWorkspacesFormIsNotFoundForEveryBuilderCall() {
        as(B, "TENANT_ADMIN");
        long theirs = (Long) data(this.service.save(new FormSaveRequest(null, "Bravo form", null, null, null, null))).get("formId");

        as(A, "TENANT_ADMIN");
        assertThat(this.service.fetch(theirs).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(this.service.save(wound(theirs, null, null)).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(this.service.status(new FormStatusRequest(theirs, FormStore.ARCHIVED)).getMessage()).isEqualTo(FormService.FORM_NOT_FOUND);
        assertThat(this.service.list(true).getData().toString()).doesNotContain("Bravo form");
        assertThat(this.store.forms.get(theirs).name).isEqualTo("Bravo form");
        assertThat(this.store.forms.get(theirs).status).isEqualTo(FormStore.DRAFT);
    }
}
