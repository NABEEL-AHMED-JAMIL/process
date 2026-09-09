package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import process.api.AnalyticsDatasetRestApi;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsDataset;
import process.model.repository.AnalyticsDatasetRepository;
import process.model.service.impl.AnalyticsDatasetServiceImpl;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether a registered dataset stays inside one workspace, and whether it can be trusted.
 *
 * <b>Why this suite exists now and could not have existed yesterday.</b> analytics_dataset was
 * created by V31 and held zero rows, because nothing wrote it. Spec 13 asks for a "guessed dataset
 * IDs" security test and the audit records that the test had no premise: with no persisted
 * datasets there were no ids to guess. Registration gives the module its first identifier on the
 * wire, so spec 11's clause -- "never allow a user to reference another tenant's dataset ID by
 * guessing an identifier" -- becomes live with this change, and
 * {@link #aGuessedDatasetIdTellsACallerNothingAboutAnotherWorkspace} is the test it was asking
 * for.
 *
 * <b>Why the repository is mocked to hand back the wrong tenant's row.</b> Because that is what
 * the real one does on this path. A Hibernate @Filter applies to queries and NOT to a load by
 * primary key, so a service that trusts the filter to scope a load by id has no scoping on that
 * path at all. Every refusal below is the service's own check being asserted, with the database's
 * contribution deliberately removed.
 *
 * <b>Every refusal has a positive control.</b> A rule that refused everybody would satisfy the
 * negative half of each pair while making the feature useless, which is exactly how the resolver
 * fix of 2026-09-08 was pinned.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsDatasetServiceTest {

    /** Two real workspaces. Neither is the platform, which is a third case and tested apart. */
    private static final Long ACME = 1001L;
    private static final Long GLOBEX = 2002L;

    private static final Long ACME_USER = 55L;
    private static final Long GLOBEX_USER = 66L;

    private static final Long OWN_DATASET_ID = 500L;
    private static final Long THEIR_DATASET_ID = 900L;
    private static final Long MISSING_DATASET_ID = 4242L;

    @Mock private AnalyticsDatasetRepository analyticsDatasetRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private UserNameResolver userNameResolver;
    @Mock private DatasetResolver datasetResolver;
    @Mock private EntityManager entityManager;

    private AnalyticsDatasetServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new AnalyticsDatasetServiceImpl(this.analyticsDatasetRepository,
            this.tenantFilterHelper, this.userNameResolver, this.datasetResolver);
        // @PersistenceContext is field injection, so there is no constructor to hand it to.
        Field entityManagerField = AnalyticsDatasetServiceImpl.class.getDeclaredField("entityManager");
        entityManagerField.setAccessible(true);
        entityManagerField.set(this.service, this.entityManager);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- reading somebody else's

    @Test
    void aTenantCannotLoadAnotherTenantsDataset() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_DATASET_ID, datasetOwnedBy(GLOBEX, THEIR_DATASET_ID));

        ResponseDto response = this.service.fetchDatasetById(THEIR_DATASET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getData())
            .as("the row reached the service, so nothing but the service's own check kept it back")
            .isNull();
    }

    @Test
    void aTenantCanLoadItsOwnDataset() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsDataset mine = datasetOwnedBy(ACME, OWN_DATASET_ID);
        whenLoadedById(OWN_DATASET_ID, mine);

        ResponseDto response = this.service.fetchDatasetById(OWN_DATASET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isSameAs(mine);
    }

    /**
     * Spec 13's "guessed dataset IDs" test, which until this service had no premise.
     *
     * "That belongs to another tenant" is a friendlier sentence and a worse one: it confirms the
     * id exists, which is enough to count another workspace's registered datasets by walking the
     * id space.
     */
    @Test
    void aGuessedDatasetIdTellsACallerNothingAboutAnotherWorkspace() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_DATASET_ID, datasetOwnedBy(GLOBEX, THEIR_DATASET_ID));
        when(this.analyticsDatasetRepository.findById(MISSING_DATASET_ID)).thenReturn(Optional.empty());

        String refused = this.service.fetchDatasetById(THEIR_DATASET_ID).getMessage();
        String missing = this.service.fetchDatasetById(MISSING_DATASET_ID).getMessage();

        assertThat(refused.replace(String.valueOf(THEIR_DATASET_ID), "#"))
            .isEqualTo(missing.replace(String.valueOf(MISSING_DATASET_ID), "#"));
    }

    /**
     * The control that the refusal is per-tenant and not a rule that refuses everybody.
     *
     * A platform admin has no tenant of their own and TenantFilterHelper disables the filter for
     * them entirely, which is the application-wide reading TenantOwnership settled. If that ever
     * has to change it should change there, for every entity at once, and not quietly here.
     */
    @Test
    void aPlatformAdminStillReachesEveryWorkspace() throws Exception {
        signedInAs(null, "PLATFORM_ADMIN", 7L);
        AnalyticsDataset theirs = datasetOwnedBy(GLOBEX, THEIR_DATASET_ID);
        whenLoadedById(THEIR_DATASET_ID, theirs);

        ResponseDto response = this.service.fetchDatasetById(THEIR_DATASET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isSameAs(theirs);
    }

    /**
     * The listing is a query, so the tenant filter really does apply to it and in a correct system
     * this loop removes nothing. It is asserted anyway because the filter is enabled by a call the
     * next person to write a listing can forget, and a short page is a better failure than a
     * complete one belonging to somebody else.
     */
    @Test
    void aListingDropsAnythingTheCallerDoesNotOwnEvenWhenTheFilterDidNot() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsDataset mine = datasetOwnedBy(ACME, OWN_DATASET_ID);
        when(this.analyticsDatasetRepository.findAllByOrderByAnalyticsDatasetIdDesc())
            .thenReturn(Arrays.asList(datasetOwnedBy(GLOBEX, THEIR_DATASET_ID), mine));

        ResponseDto response = this.service.fetchAllDatasets();

        @SuppressWarnings("unchecked")
        List<AnalyticsDataset> data = (List<AnalyticsDataset>) response.getData();
        assertThat(data).containsExactly(mine);
    }

    // ---------------------------------------------------------------- changing somebody else's

    @Test
    void aTenantCannotDeleteAnotherTenantsDataset() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_DATASET_ID, datasetOwnedBy(GLOBEX, THEIR_DATASET_ID));

        ResponseDto response = this.service.deleteDataset(THEIR_DATASET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDatasetRepository, never()).delete(any(AnalyticsDataset.class));
    }

    @Test
    void aTenantCanDeleteItsOwnDataset() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsDataset mine = datasetOwnedBy(ACME, OWN_DATASET_ID);
        whenLoadedById(OWN_DATASET_ID, mine);

        ResponseDto response = this.service.deleteDataset(OWN_DATASET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.analyticsDatasetRepository).delete(mine);
    }

    // ---------------------------------------------------------------- registering

    /**
     * The endpoint binds the request body onto the entity, so a caller can put any tenant on the
     * wire. None of it is read: the row is built from the signed-in context.
     */
    @Test
    void registeringIgnoresTheTenantAndTheAuthorOnTheWire() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenResolved("orders/2026/*.csv", DatasetRef.Format.CSV);
        whenSavedReturnTheRow();

        AnalyticsDataset payload = payload();
        payload.setTenantId(GLOBEX);
        payload.setCreatedBy(GLOBEX_USER);

        this.service.registerDataset(payload);

        AnalyticsDataset stored = capturedSave();
        assertThat(stored.getTenantId()).isEqualTo(ACME);
        assertThat(stored.getCreatedBy())
            .as("AuditListener stamps the author at persist time from the same context, so a "
                + "createdBy on the wire must not survive to be stamped over")
            .isNull();
    }

    @Test
    void aCallerWithNoWorkspaceCannotRegisterAtAll() throws Exception {
        // Not a platform admin, and no tenant either. TenantOwnership's reading is that such a
        // caller owns nothing, so it fails closed rather than filing the row under no workspace,
        // where the plain-equality filter would leave it visible to platform admins only.
        signedInAs(null, "TENANT_USER", 99L);

        ResponseDto response = this.service.registerDataset(payload());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDatasetRepository, never()).save(any(AnalyticsDataset.class));
    }

    /**
     * Registration is an authorization decision, not a note to self.
     *
     * Without this, the registry is somewhere to park the alias of a connection the caller cannot
     * open -- which reads to a user as a dataset they own and to the resolver as a refusal, and
     * which would let the registry be walked to learn which aliases exist elsewhere.
     */
    @Test
    void aDatasetCannotBeRegisteredAgainstAConnectionTheCallerCannotReach() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        when(this.datasetResolver.resolve(anyString(), anyString()))
            .thenThrow(new AnalyticsException("Storage connection not found."));

        ResponseDto response = this.service.registerDataset(payload());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage())
            .as("the resolver's own words, which are the same for absent and for forbidden, so "
                + "registration cannot be walked to learn which aliases other workspaces own")
            .isEqualTo("Storage connection not found.");
        verify(this.analyticsDatasetRepository, never()).save(any(AnalyticsDataset.class));
    }

    /** The control for the test above: a connection the caller can reach registers. */
    @Test
    void aDatasetIsRegisteredAgainstAConnectionTheCallerCanReach() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenResolved("orders/2026/*.csv", DatasetRef.Format.CSV);
        whenSavedReturnTheRow();

        ResponseDto response = this.service.registerDataset(payload());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(capturedSave().getConnectionAlias()).isEqualTo("etl-bucket");
    }

    /**
     * The format label is derived, never accepted.
     *
     * The column exists so a listing can say what a dataset is without opening it. A label the
     * caller could set to anything would be a listing that lies -- and the reader still picks its
     * reader from the path, so the lie would never be corrected by anything failing.
     */
    @Test
    void theFormatLabelComesFromTheResolverAndNotFromThePayload() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenResolved("orders/2026/part-0.parquet", DatasetRef.Format.PARQUET);
        whenSavedReturnTheRow();

        AnalyticsDataset payload = payload();
        payload.setDatasetPath("orders/2026/part-0.parquet");
        payload.setDatasetFormat("CSV");

        this.service.registerDataset(payload);

        assertThat(capturedSave().getDatasetFormat()).isEqualTo("PARQUET");
    }

    /**
     * What is stored is the string the resolver accepted, not the one the caller sent.
     *
     * The resolver strips leading slashes and applies the path allow-list. Keeping its cleaned
     * path means a stored row is by construction a path that passed, rather than one that merely
     * contained a path that passed.
     */
    @Test
    void thePathStoredIsTheOneTheResolverAccepted() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenResolved("orders/2026/*.csv", DatasetRef.Format.CSV);
        whenSavedReturnTheRow();

        AnalyticsDataset payload = payload();
        payload.setDatasetPath("/orders/2026/*.csv");

        this.service.registerDataset(payload);

        assertThat(capturedSave().getDatasetPath()).isEqualTo("orders/2026/*.csv");
    }

    @Test
    void aRegistrationWithNoNameIsRefusedBeforeAnythingIsResolved() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);

        AnalyticsDataset payload = payload();
        payload.setDatasetName("   ");

        ResponseDto response = this.service.registerDataset(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDatasetRepository, never()).save(any(AnalyticsDataset.class));
    }

    // ---------------------------------------------------------------- the endpoint

    /**
     * The floor, asserted because it is one annotation away from being absent.
     *
     * The role is only the floor -- which rows a caller may reach is settled per request against
     * the row's own tenant -- but a controller that lost this annotation would be reachable by
     * anybody the filter chain authenticated at all.
     */
    @Test
    void theEndpointsAreBehindTheSameRoleAsTheRestOfAnalyticsStudio() {
        PreAuthorize preAuthorize = AnnotatedElementUtils
            .findMergedAnnotation(AnalyticsDatasetRestApi.class, PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('TENANT_USER')");
    }

    // ---------------------------------------------------------------- helpers

    private static void signedInAs(Long tenantId, String role, Long appUserId) {
        TenantContext.set(tenantId, role, appUserId, "user@test");
    }

    private void whenLoadedById(Long analyticsDatasetId, AnalyticsDataset dataset) {
        when(this.analyticsDatasetRepository.findById(analyticsDatasetId))
            .thenReturn(Optional.of(dataset));
    }

    private void whenResolved(String cleanPath, DatasetRef.Format format) throws Exception {
        // The test lives in process.analytics, so it can build the DatasetRef the resolver would
        // have returned. Everywhere else in the application that constructor is unreachable, which
        // is what makes the resolver the only way to obtain one.
        when(this.datasetResolver.resolve(anyString(), anyString()))
            .thenReturn(new DatasetRef(null, "etl-bucket", cleanPath, format));
    }

    private void whenSavedReturnTheRow() {
        when(this.analyticsDatasetRepository.save(any(AnalyticsDataset.class)))
            .thenAnswer(invocation -> {
                AnalyticsDataset saved = invocation.getArgument(0);
                if (saved.getAnalyticsDatasetId() == null) {
                    saved.setAnalyticsDatasetId(OWN_DATASET_ID);
                }
                return saved;
            });
    }

    private AnalyticsDataset capturedSave() {
        ArgumentCaptor<AnalyticsDataset> captor = ArgumentCaptor.forClass(AnalyticsDataset.class);
        verify(this.analyticsDatasetRepository).save(captor.capture());
        return captor.getValue();
    }

    private static AnalyticsDataset datasetOwnedBy(Long tenantId, Long analyticsDatasetId) {
        AnalyticsDataset dataset = new AnalyticsDataset();
        dataset.setAnalyticsDatasetId(analyticsDatasetId);
        dataset.setTenantId(tenantId);
        dataset.setDatasetName("last night's export");
        dataset.setConnectionAlias("etl-bucket");
        dataset.setDatasetPath("orders/2026/*.csv");
        dataset.setDatasetFormat("CSV");
        return dataset;
    }

    private static AnalyticsDataset payload() {
        AnalyticsDataset payload = new AnalyticsDataset();
        payload.setDatasetName("last night's export");
        payload.setConnectionAlias("etl-bucket");
        payload.setDatasetPath("orders/2026/*.csv");
        return payload;
    }

}
