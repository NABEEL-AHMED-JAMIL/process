package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.TaskForm;
import process.model.pojo.TaskFormField;
import process.model.pojo.Tenant;
import process.model.repository.TaskFormRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A form definition describes one pipeline's payload for one tenant -- the same per-workspace
 * boundary Storage Connections and Kafka Connections already enforce. It used to also match a
 * null-tenant "shared with every tenant" row (the same reading Kafka Connections was walked back
 * from -- see KafkaConnectionProfileRepository's own history), which meant a form's XML-tag
 * schema for a pipeline could leak from one tenant's admin screen into another's, and silently
 * drive a different tenant's task-configuration screen too.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class TaskFormServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long DEFAULT_TENANT = 1L;
    private static final String PIPELINE = "F768926";

    @Mock private TaskFormRepository taskFormRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserNameResolver userNameResolver;

    private TaskFormServiceImpl service;
    private TaskForm tenantAForm;

    @BeforeEach
    void setUp() {
        this.service = new TaskFormServiceImpl(this.taskFormRepository, this.tenantRepository, this.userNameResolver);

        this.tenantAForm = new TaskForm();
        this.tenantAForm.setTaskFormId(55L);
        this.tenantAForm.setPipelineId(PIPELINE);
        this.tenantAForm.setFormName("Tenant A's form");
        this.tenantAForm.setTenantId(TENANT_A);
        this.tenantAForm.setFormStatus(Status.Active);
        this.tenantAForm.setFields(new java.util.ArrayList<>(Collections.singletonList(field("bucket", "Bucket"))));

        lenient().when(this.userNameResolver.namesFor(any())).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private TaskFormField field(String tag, String label) {
        TaskFormField f = new TaskFormField();
        f.setTagKey(tag);
        f.setLabel(label);
        f.setFieldType("text");
        return f;
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "user@tenant.example");
    }

    // ---- listForms: a tenant sees only its own rows -------------------------------------------

    @Test
    void aTenantNeverSeesAnotherTenantsForms() {
        this.actAsTenant(TENANT_B);
        when(this.taskFormRepository.findAllByTenantIdAndFormStatusNotOrderByTaskFormIdDesc(TENANT_B, Status.Delete))
            .thenReturn(Collections.emptyList());

        ResponseDto response = this.service.listForms();

        assertThat((List<?>) response.getData()).isEmpty();
        verify(this.taskFormRepository, never()).findAllByFormStatusNot(any());
    }

    @Test
    void aTenantSeesItsOwnForms() {
        this.actAsTenant(TENANT_A);
        when(this.taskFormRepository.findAllByTenantIdAndFormStatusNotOrderByTaskFormIdDesc(TENANT_A, Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.listForms();

        assertThat((List<TaskForm>) (List<?>) response.getData()).containsExactly(this.tenantAForm);
    }

    @Test
    void aPlatformAdminSeesEveryTenantsForms() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.taskFormRepository.findAllByFormStatusNot(Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.listForms();

        assertThat((List<TaskForm>) (List<?>) response.getData()).containsExactly(this.tenantAForm);
        verify(this.taskFormRepository, never())
            .findAllByTenantIdAndFormStatusNotOrderByTaskFormIdDesc(anyLong(), any());
    }

    @Test
    void aTenantlessNonAdminCallerSeesNothingRatherThanQueryingWithANullTenant() {
        TenantContext.set(null, "TENANT_ADMIN", 9000L, "no-tenant@example.com");

        ResponseDto response = this.service.listForms();

        assertThat((List<?>) response.getData()).isEmpty();
        verify(this.taskFormRepository, never())
            .findAllByTenantIdAndFormStatusNotOrderByTaskFormIdDesc(any(), any());
        verify(this.taskFormRepository, never()).findAllByFormStatusNot(any());
    }

    // ---- formForPipeline: strict, no shared fallback -------------------------------------------

    @Test
    void aPipelinesFormNeverResolvesToAnotherTenantsDefinition() {
        this.actAsTenant(TENANT_B);
        when(this.taskFormRepository.findAllByPipelineIdAndTenantIdAndFormStatusNot(PIPELINE, TENANT_B, Status.Delete))
            .thenReturn(Collections.emptyList());

        ResponseDto response = this.service.formForPipeline(PIPELINE, null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).contains("No form is defined");
    }

    @Test
    void aPipelinesFormResolvesToTheCallersOwnTenant() {
        this.actAsTenant(TENANT_A);
        when(this.taskFormRepository.findAllByPipelineIdAndTenantIdAndFormStatusNot(PIPELINE, TENANT_A, Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.formForPipeline(PIPELINE, null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isEqualTo(this.tenantAForm);
    }

    /**
     * Added 2026-09-07: a platform admin has no tenant of its own to match a form's tenant_id
     * against, so the strict tenant-scoped query above always came back empty for them even when
     * the pipeline plainly had a form -- the Pipeline picker (fed by listPipelines, which platform
     * admins see every tenant's row of) offered a pipeline whose own form then silently failed to
     * load. Reading is now more forgiving than writing: a platform admin's request matches across
     * every tenant's forms instead of comparing against a tenant id that does not exist.
     */
    @Test
    void aPlatformAdminsRequestMatchesThePipelineAcrossEveryTenant() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.taskFormRepository.findAllByFormStatusNot(Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.formForPipeline(PIPELINE, null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isEqualTo(this.tenantAForm);
        verify(this.taskFormRepository, never())
            .findAllByPipelineIdAndTenantIdAndFormStatusNot(any(), any(), any());
    }

    /** The control: a platform admin gets nothing back for a pipeline no tenant has defined. */
    @Test
    void aPlatformAdminGetsNothingForAPipelineNoTenantHasDefined() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.taskFormRepository.findAllByFormStatusNot(Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.formForPipeline("F000000-not-defined-anywhere", null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).contains("No form is defined");
    }

    // ---- saveForm: create always stamps the caller's own tenant, never a shared row -----------

    @Test
    void aNewFormIsStampedWithTheCreatorsOwnTenant() {
        this.actAsTenant(TENANT_A);
        when(this.taskFormRepository.findAllByPipelineIdAndTenantIdAndFormStatusNot(PIPELINE, TENANT_A, Status.Delete))
            .thenReturn(Collections.emptyList());
        when(this.taskFormRepository.save(any(TaskForm.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskForm submitted = new TaskForm();
        submitted.setPipelineId(PIPELINE);
        submitted.setFormName("New form");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        TaskForm saved = (TaskForm) response.getData();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void aTenantlessCallersNewFormIsFiledUnderTheDefaultTenant() {
        // A platform admin with no tenant context (the "New form" dialog offers no tenant
        // picker) -- creating here would otherwise produce a form no tenant's task screen can
        // ever see (see the strict tenant-scoped repository queries above), so it is filed under
        // the seeded "default" tenant instead of refused: only that tenant can use it, and it
        // stays reachable for anyone signed in as it to edit or delete.
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        Tenant defaultTenant = new Tenant();
        defaultTenant.setTenantId(DEFAULT_TENANT);
        when(this.tenantRepository.findByTenantCode(TenantSeedService.DEFAULT_TENANT_CODE))
            .thenReturn(Optional.of(defaultTenant));
        when(this.taskFormRepository.findAllByPipelineIdAndTenantIdAndFormStatusNot(
            PIPELINE, DEFAULT_TENANT, Status.Delete)).thenReturn(Collections.emptyList());
        when(this.taskFormRepository.save(any(TaskForm.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskForm submitted = new TaskForm();
        submitted.setPipelineId(PIPELINE);
        submitted.setFormName("New form");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        TaskForm saved = (TaskForm) response.getData();
        assertThat(saved.getTenantId()).isEqualTo(DEFAULT_TENANT);
    }

    @Test
    void aTenantlessCallerCannotCreateAFormWhenNoDefaultTenantExists() {
        // Defensive: TenantSeedService seeds this tenant at startup, so this should not happen
        // in practice, but a missing default tenant must fail with an explainable message rather
        // than an NPE from an absent Optional.
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.tenantRepository.findByTenantCode(TenantSeedService.DEFAULT_TENANT_CODE))
            .thenReturn(Optional.empty());

        TaskForm submitted = new TaskForm();
        submitted.setPipelineId(PIPELINE);
        submitted.setFormName("New form");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("No default tenant");
        verify(this.taskFormRepository, never()).save(any());
    }

    // ---- edit/delete: a tenant cannot touch another tenant's row (already-strict write path) ---

    @Test
    void aTenantCannotEditAnotherTenantsForm() {
        this.actAsTenant(TENANT_B);
        when(this.taskFormRepository.findByTaskFormIdAndFormStatusNot(55L, Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));

        TaskForm submitted = new TaskForm();
        submitted.setTaskFormId(55L);
        submitted.setPipelineId(PIPELINE);
        submitted.setFormName("Repointed");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("another tenant");
        verify(this.taskFormRepository, never()).save(any());
    }

    @Test
    void aTenantCannotDeleteAnotherTenantsForm() {
        this.actAsTenant(TENANT_B);
        when(this.taskFormRepository.findByTaskFormIdAndFormStatusNot(55L, Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));

        ResponseDto response = this.service.deleteForm(55L);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        verify(this.taskFormRepository, never()).save(any());
    }

    /** The control: the platform admin can still manage a form regardless of whose tenant it is. */
    @Test
    void aPlatformAdminCanStillEditAnyExistingForm() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.taskFormRepository.findByTaskFormIdAndFormStatusNot(55L, Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));
        when(this.taskFormRepository.save(any(TaskForm.class))).thenAnswer(inv -> inv.getArgument(0));

        TaskForm submitted = new TaskForm();
        submitted.setTaskFormId(55L);
        submitted.setPipelineId(PIPELINE);
        submitted.setFormName("Renamed by platform admin");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
    }
}
