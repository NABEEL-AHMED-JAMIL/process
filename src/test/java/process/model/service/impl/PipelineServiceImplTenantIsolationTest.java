package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.dto.PipelineRowDto;
import process.model.projection.PipelineRowProjection;
import process.model.pojo.PipelineField;
import process.model.pojo.Tenant;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.pojo.SourceTaskType;
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
import static org.mockito.Mockito.mock;
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
public class PipelineServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long DEFAULT_TENANT = 1L;
    private static final String PIPELINE = "F768926";

    @Mock private PipelineRepository pipelineRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserNameResolver userNameResolver;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;

    private PipelineServiceImpl service;

    /** A topic with no tenant -- visible to every workspace -- so it never trips the ownership check here. */
    private static final long TOPIC_ID = 9001L;
    private Pipeline tenantAForm;

    @BeforeEach
    void setUp() {
        this.service = new PipelineServiceImpl(this.pipelineRepository, this.tenantRepository, this.userNameResolver, this.sourceTaskTypeRepository);
        this.theTopicExists();

        this.tenantAForm = new Pipeline();
        this.tenantAForm.setSourceTaskTypeId(TOPIC_ID);
        this.tenantAForm.setPipelineKey(55L);
        this.tenantAForm.setPipelineId(PIPELINE);
        this.tenantAForm.setPipelineName("Tenant A's form");
        this.tenantAForm.setTenantId(TENANT_A);
        this.tenantAForm.setStatus(Status.Active);
        this.tenantAForm.setFields(new java.util.ArrayList<>(Collections.singletonList(field("bucket", "Bucket"))));

        lenient().when(this.userNameResolver.namesFor(any())).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void theTopicExists() {
        SourceTaskType topic = new SourceTaskType();
        topic.setSourceTaskTypeId(TOPIC_ID);
        topic.setStatus(process.model.enums.Status.Active);
        org.mockito.Mockito.lenient().when(this.sourceTaskTypeRepository.findById(TOPIC_ID)).thenReturn(java.util.Optional.of(topic));
    }

    private PipelineField field(String tag, String label) {
        PipelineField f = new PipelineField();
        f.setTagKey(tag);
        f.setLabel(label);
        f.setFieldType("text");
        return f;
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "user@tenant.example");
    }

    // ---- listForms: a tenant sees only its own rows -------------------------------------------

    /** A list row as the repository projects it: the tenant A form, minus its fields, plus counts. */
    private PipelineRowProjection tenantARow() {
        Long key = this.tenantAForm.getPipelineKey();
        PipelineRowProjection row = mock(PipelineRowProjection.class);
        when(row.getPipelineKey()).thenReturn(key);
        when(row.getPipelineId()).thenReturn(PIPELINE);
        when(row.getTenantId()).thenReturn(TENANT_A);
        when(row.getFieldCount()).thenReturn(3L);
        when(row.getRequiredCount()).thenReturn(1L);
        return row;
    }

    @Test
    void aTenantNeverSeesAnotherTenantsForms() {
        this.actAsTenant(TENANT_B);
        when(this.pipelineRepository.listRowsForTenant(TENANT_B)).thenReturn(Collections.emptyList());

        ResponseDto response = this.service.listForms();

        assertThat((List<?>) response.getData()).isEmpty();
        verify(this.pipelineRepository, never()).listRows();
    }

    @Test
    void aTenantSeesItsOwnForms() {
        this.actAsTenant(TENANT_A);
        // Built before the stubbing: a mock set up inside thenReturn(...) is a nested, unfinished stub.
        PipelineRowProjection row = this.tenantARow();
        when(this.pipelineRepository.listRowsForTenant(TENANT_A)).thenReturn(Collections.singletonList(row));

        ResponseDto response = this.service.listForms();

        List<PipelineRowDto> rows = (List<PipelineRowDto>) (List<?>) response.getData();
        assertThat(rows).extracting(PipelineRowDto::getPipelineId).containsExactly(PIPELINE);
        // The row says how many fields there are rather than carrying them.
        assertThat(rows.get(0).getFieldCount()).isEqualTo(3L);
        assertThat(rows.get(0).getRequiredCount()).isEqualTo(1L);
    }

    @Test
    void aPlatformAdminSeesEveryTenantsForms() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        PipelineRowProjection row = this.tenantARow();
        when(this.pipelineRepository.listRows()).thenReturn(Collections.singletonList(row));

        ResponseDto response = this.service.listForms();

        assertThat((List<PipelineRowDto>) (List<?>) response.getData())
            .extracting(PipelineRowDto::getPipelineId).containsExactly(PIPELINE);
        verify(this.pipelineRepository, never()).listRowsForTenant(anyLong());
    }

    @Test
    void aTenantlessNonAdminCallerSeesNothingRatherThanQueryingWithANullTenant() {
        TenantContext.set(null, "TENANT_ADMIN", 9000L, "no-tenant@example.com");

        ResponseDto response = this.service.listForms();

        assertThat((List<?>) response.getData()).isEmpty();
        verify(this.pipelineRepository, never()).listRowsForTenant(any());
        verify(this.pipelineRepository, never()).listRows();
    }

    // ---- fieldsFor: the fields of one row, scoped like delete ----------------------------------

    @Test
    void aTenantCannotReadAnotherTenantsFields() {
        this.actAsTenant(TENANT_B);
        when(this.pipelineRepository.findByPipelineKeyAndStatusNot(this.tenantAForm.getPipelineKey(), Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));

        ResponseDto response = this.service.fieldsFor(this.tenantAForm.getPipelineKey());

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getData()).isNull();
    }

    @Test
    void aTenantReadsItsOwnFields() {
        this.actAsTenant(TENANT_A);
        when(this.pipelineRepository.findByPipelineKeyAndStatusNot(this.tenantAForm.getPipelineKey(), Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));

        ResponseDto response = this.service.fieldsFor(this.tenantAForm.getPipelineKey());

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isSameAs(this.tenantAForm.getFields());
    }

    // ---- formForPipeline: strict, no shared fallback -------------------------------------------

    @Test
    void aPipelinesFormNeverResolvesToAnotherTenantsDefinition() {
        this.actAsTenant(TENANT_B);
        when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(PIPELINE, TENANT_B, Status.Delete))
            .thenReturn(Collections.emptyList());

        ResponseDto response = this.service.formForPipeline(PIPELINE, null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).contains("No pipeline is defined");
    }

    @Test
    void aPipelinesFormResolvesToTheCallersOwnTenant() {
        this.actAsTenant(TENANT_A);
        when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(PIPELINE, TENANT_A, Status.Delete))
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
        when(this.pipelineRepository.findAllByStatusNot(Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.formForPipeline(PIPELINE, null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isEqualTo(this.tenantAForm);
        verify(this.pipelineRepository, never())
            .findAllByPipelineIdAndTenantIdAndStatusNot(any(), any(), any());
    }

    /** The control: a platform admin gets nothing back for a pipeline no tenant has defined. */
    @Test
    void aPlatformAdminGetsNothingForAPipelineNoTenantHasDefined() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.pipelineRepository.findAllByStatusNot(Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantAForm));

        ResponseDto response = this.service.formForPipeline("F000000-not-defined-anywhere", null);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).contains("No pipeline is defined");
    }

    // ---- saveForm: create always stamps the caller's own tenant, never a shared row -----------

    @Test
    void aNewFormIsStampedWithTheCreatorsOwnTenant() {
        this.actAsTenant(TENANT_A);
        when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(PIPELINE, TENANT_A, Status.Delete))
            .thenReturn(Collections.emptyList());
        when(this.pipelineRepository.save(any(Pipeline.class))).thenAnswer(inv -> inv.getArgument(0));

        Pipeline submitted = new Pipeline();
        submitted.setSourceTaskTypeId(TOPIC_ID);
        submitted.setPipelineId(PIPELINE);
        submitted.setPipelineName("New form");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        Pipeline saved = (Pipeline) response.getData();
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
        when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(
            PIPELINE, DEFAULT_TENANT, Status.Delete)).thenReturn(Collections.emptyList());
        when(this.pipelineRepository.save(any(Pipeline.class))).thenAnswer(inv -> inv.getArgument(0));

        Pipeline submitted = new Pipeline();
        submitted.setSourceTaskTypeId(TOPIC_ID);
        submitted.setPipelineId(PIPELINE);
        submitted.setPipelineName("New form");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        Pipeline saved = (Pipeline) response.getData();
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

        Pipeline submitted = new Pipeline();
        submitted.setSourceTaskTypeId(TOPIC_ID);
        submitted.setPipelineId(PIPELINE);
        submitted.setPipelineName("New form");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("No default tenant");
        verify(this.pipelineRepository, never()).save(any());
    }

    // ---- edit/delete: a tenant cannot touch another tenant's row (already-strict write path) ---

    @Test
    void aTenantCannotEditAnotherTenantsForm() {
        this.actAsTenant(TENANT_B);
        when(this.pipelineRepository.findByPipelineKeyAndStatusNot(55L, Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));

        Pipeline submitted = new Pipeline();
        submitted.setSourceTaskTypeId(TOPIC_ID);
        submitted.setPipelineKey(55L);
        submitted.setPipelineId(PIPELINE);
        submitted.setPipelineName("Repointed");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("another tenant");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void aTenantCannotDeleteAnotherTenantsForm() {
        this.actAsTenant(TENANT_B);
        when(this.pipelineRepository.findByPipelineKeyAndStatusNot(55L, Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));

        ResponseDto response = this.service.deleteForm(55L);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        verify(this.pipelineRepository, never()).save(any());
    }

    /** The control: the platform admin can still manage a form regardless of whose tenant it is. */
    @Test
    void aPlatformAdminCanStillEditAnyExistingForm() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.pipelineRepository.findByPipelineKeyAndStatusNot(55L, Status.Delete))
            .thenReturn(Optional.of(this.tenantAForm));
        when(this.pipelineRepository.save(any(Pipeline.class))).thenAnswer(inv -> inv.getArgument(0));

        Pipeline submitted = new Pipeline();
        submitted.setSourceTaskTypeId(TOPIC_ID);
        submitted.setPipelineKey(55L);
        submitted.setPipelineId(PIPELINE);
        submitted.setPipelineName("Renamed by platform admin");
        submitted.setFields(Collections.singletonList(field("bucket", "Bucket")));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
    }
}
