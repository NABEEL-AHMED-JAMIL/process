package process.analytics;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import process.api.AnalyticsLibraryRestApi;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsDashboardWidget;
import process.model.pojo.AnalyticsQuery;
import process.model.pojo.AnalyticsQueryRun;
import process.model.pojo.AuditListener;
import process.model.repository.AnalyticsDashboardWidgetRepository;
import process.model.repository.AnalyticsQueryRepository;
import process.model.repository.AnalyticsQueryRunRepository;
import process.model.service.impl.AnalyticsQueryLibraryServiceImpl;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.EntityManager;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether a saved query and its history stay inside one workspace.
 *
 * This is the test the module is owed rather than the test the code makes easy. On 2026-09-08 the
 * exact same distinction -- "is this row visible to me" versus "is this row mine" -- was a live
 * cross-tenant read here: DatasetResolver filtered storage connections with
 * TenantOwnership.isVisibleToCaller, which returns true for a platform-owned row, and every
 * tenant user could reach the whole storage estate. A saved query is a smaller thing than a
 * bucket and it leaks the same three facts: which connection somebody uses, which path they read,
 * and the SQL they wrote against it.
 *
 * <b>Why the repository is mocked to hand back the wrong tenant's row.</b> Because that is what
 * the real one does. A Hibernate @Filter applies to queries and NOT to a load by primary key, so
 * a service that trusts the filter to scope a load by id is a service with no scoping at all on
 * that path. Every refusal below is therefore the service's own check being asserted, with the
 * database's contribution deliberately removed.
 *
 * <b>Every refusal has a positive control.</b> A rule that refused everybody would satisfy the
 * negative half of each pair while making the feature useless, and that is exactly how the
 * resolver fix earlier today was pinned.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsQueryLibraryTest {

    /** Two real workspaces. Neither is the platform, which is a third case and tested apart. */
    private static final Long ACME = 1001L;
    private static final Long GLOBEX = 2002L;

    private static final Long ACME_USER = 55L;
    private static final Long GLOBEX_USER = 66L;

    private static final Long OWN_QUERY_ID = 500L;
    private static final Long THEIR_QUERY_ID = 900L;
    private static final Long MISSING_QUERY_ID = 4242L;

    /**
     * Anything that would say WHERE the data is rather than WHICH connection reaches it, plus the
     * words a credential arrives under. Neither table may name one.
     */
    private static final List<String> LOCATION_AND_CREDENTIAL_WORDS = Arrays.asList(
        "bucket", "endpoint", "region", "url", "host", "port", "secret", "accesskey", "password",
        "credential", "connectionstring");

    @Mock private AnalyticsQueryRepository analyticsQueryRepository;
    @Mock private AnalyticsQueryRunRepository analyticsQueryRunRepository;
    @Mock private AnalyticsDashboardWidgetRepository analyticsDashboardWidgetRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private UserNameResolver userNameResolver;
    @Mock private EntityManager entityManager;

    private AnalyticsQueryLibraryServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new AnalyticsQueryLibraryServiceImpl(this.analyticsQueryRepository,
            this.analyticsQueryRunRepository, this.analyticsDashboardWidgetRepository,
            this.tenantFilterHelper, this.userNameResolver);
        // @PersistenceContext is field injection, so there is no constructor to hand it to.
        Field entityManagerField = AnalyticsQueryLibraryServiceImpl.class.getDeclaredField("entityManager");
        entityManagerField.setAccessible(true);
        entityManagerField.set(this.service, this.entityManager);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- reading somebody else's

    @Test
    void aTenantCannotLoadAnotherTenantsSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_QUERY_ID, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID));

        ResponseDto response = this.service.fetchQueryById(THEIR_QUERY_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getData())
            .as("the row reached the service, so nothing but the service's own check kept it back")
            .isNull();
    }

    @Test
    void aTenantCanLoadItsOwnSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsQuery mine = savedQueryOwnedBy(ACME, OWN_QUERY_ID);
        whenLoadedById(OWN_QUERY_ID, mine);

        ResponseDto response = this.service.fetchQueryById(OWN_QUERY_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isSameAs(mine);
    }

    /**
     * The refusal must not be a lookup service for other people's ids.
     *
     * "That belongs to another tenant" is a friendlier sentence and a worse one: it confirms the
     * row exists, which is enough to count another workspace's saved work by walking the ids.
     */
    @Test
    void aRefusedQueryAndAMissingQueryAreIndistinguishable() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_QUERY_ID, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID));
        when(this.analyticsQueryRepository.findByAnalyticsQueryId(MISSING_QUERY_ID))
            .thenReturn(Optional.empty());

        String refused = this.service.fetchQueryById(THEIR_QUERY_ID).getMessage();
        String missing = this.service.fetchQueryById(MISSING_QUERY_ID).getMessage();

        assertThat(refused.replace(String.valueOf(THEIR_QUERY_ID), "#"))
            .isEqualTo(missing.replace(String.valueOf(MISSING_QUERY_ID), "#"));
    }

    /**
     * The control that a refusal is per-tenant and not a rule that refuses everybody.
     *
     * A platform admin has no tenant of their own and TenantFilterHelper disables the filter for
     * them entirely, which is the application-wide reading TenantOwnership settled. If this ever
     * has to change it should change there, for every entity at once, and not quietly here.
     */
    @Test
    void aPlatformAdminStillReachesEveryWorkspace() throws Exception {
        signedInAs(null, "PLATFORM_ADMIN", 7L);
        AnalyticsQuery theirs = savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID);
        whenLoadedById(THEIR_QUERY_ID, theirs);

        ResponseDto response = this.service.fetchQueryById(THEIR_QUERY_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isSameAs(theirs);
    }

    // ---------------------------------------------------------------- changing somebody else's

    @Test
    void aTenantCannotDeleteAnotherTenantsSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_QUERY_ID, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID));

        ResponseDto response = this.service.deleteQuery(THEIR_QUERY_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsQueryRepository, never()).delete(any(AnalyticsQuery.class));
    }

    @Test
    void aTenantCanDeleteItsOwnSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsQuery mine = savedQueryOwnedBy(ACME, OWN_QUERY_ID);
        whenLoadedById(OWN_QUERY_ID, mine);

        ResponseDto response = this.service.deleteQuery(OWN_QUERY_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.analyticsQueryRepository).delete(mine);
    }

    @Test
    void aTenantCannotRenameAnotherTenantsSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsQuery theirs = savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID);
        whenLoadedById(THEIR_QUERY_ID, theirs);

        ResponseDto response = this.service.renameQuery(THEIR_QUERY_ID, "mine now");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(theirs.getQueryName()).isEqualTo("last night's export");
        verify(this.analyticsQueryRepository, never()).save(any(AnalyticsQuery.class));
    }

    @Test
    void aTenantCanRenameItsOwnSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(OWN_QUERY_ID, savedQueryOwnedBy(ACME, OWN_QUERY_ID));
        whenSavedReturnTheRow();

        ResponseDto response = this.service.renameQuery(OWN_QUERY_ID, "  the one with the bad rows  ");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(((AnalyticsQuery) response.getData()).getQueryName())
            .isEqualTo("the one with the bad rows");
    }

    /**
     * saveQuery carries an id, so it is a second way in to another workspace's row and needs the
     * same check the explicit rename has.
     */
    @Test
    void aTenantCannotOverwriteAnotherTenantsSavedQueryByPostingItsId() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsQuery theirs = savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID);
        whenLoadedById(THEIR_QUERY_ID, theirs);

        AnalyticsQuery payload = payload();
        payload.setAnalyticsQueryId(THEIR_QUERY_ID);
        payload.setQueryText("select * from dataset");

        ResponseDto response = this.service.saveQuery(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(theirs.getQueryText()).isEqualTo("select 1");
        verify(this.analyticsQueryRepository, never()).save(any(AnalyticsQuery.class));
    }

    // ---------------------------------------------------------------- writing into somebody else's

    /**
     * The endpoint binds the request body onto the entity, so a caller can put any tenant on the
     * wire. None of it is read: the row is built from the signed-in context.
     */
    @Test
    void savingIgnoresTheTenantAndTheAuthorOnTheWire() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenSavedReturnTheRow();

        AnalyticsQuery payload = payload();
        payload.setTenantId(GLOBEX);
        payload.setCreatedBy(GLOBEX_USER);

        this.service.saveQuery(payload);

        AnalyticsQuery stored = capturedSave();
        assertThat(stored.getTenantId()).isEqualTo(ACME);
        assertThat(stored.getCreatedBy())
            .as("AuditListener stamps the author at persist time from the same context, so a "
                + "createdBy on the wire must not survive to be stamped over")
            .isNull();
    }

    @Test
    void aCallerWithNoWorkspaceCannotSaveAtAll() throws Exception {
        // Not a platform admin, and no tenant either. TenantOwnership's reading is that such a
        // caller owns nothing, so it fails closed rather than filing the row under no workspace,
        // where the plain-equality filter would leave it visible to platform admins only.
        signedInAs(null, "TENANT_USER", 99L);

        ResponseDto response = this.service.saveQuery(payload());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsQueryRepository, never()).save(any(AnalyticsQuery.class));
    }

    // ---------------------------------------------------------------- history

    @Test
    void aRunIsRecordedAgainstTheCallersWorkspace() {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenRunSavedReturnTheRow();

        AnalyticsQueryRun submitted = run();
        submitted.setTenantId(GLOBEX);

        this.service.recordRun(submitted);

        AnalyticsQueryRun stored = capturedRunSave();
        assertThat(stored.getTenantId()).isEqualTo(ACME);
        assertThat(stored.getRowCount()).isEqualTo(42L);
        assertThat(stored.getDurationMs()).isEqualTo(310L);
        assertThat(stored.getRunStatus()).isEqualTo(AnalyticsQueryRun.STATUS_SUCCESS);
    }

    /**
     * A run cannot be filed under another tenant's saved query.
     *
     * The read really happened and is still recorded -- dropping it would lose the audit row that
     * is the whole point -- but the link is cut, so nobody finds somebody else's activity in
     * their own query's history.
     */
    @Test
    void aRunCannotBeAttributedToAnotherTenantsSavedQuery() {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_QUERY_ID, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID));
        whenRunSavedReturnTheRow();

        AnalyticsQueryRun submitted = run();
        submitted.setAnalyticsQueryId(THEIR_QUERY_ID);

        this.service.recordRun(submitted);

        AnalyticsQueryRun stored = capturedRunSave();
        assertThat(stored.getAnalyticsQueryId()).isNull();
        assertThat(stored.getTenantId()).isEqualTo(ACME);
    }

    @Test
    void aRunAgainstTheCallersOwnSavedQueryKeepsTheLink() {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(OWN_QUERY_ID, savedQueryOwnedBy(ACME, OWN_QUERY_ID));
        whenRunSavedReturnTheRow();

        AnalyticsQueryRun submitted = run();
        submitted.setAnalyticsQueryId(OWN_QUERY_ID);

        this.service.recordRun(submitted);

        assertThat(capturedRunSave().getAnalyticsQueryId()).isEqualTo(OWN_QUERY_ID);
    }

    /**
     * The one thing this table must never end up holding.
     *
     * An engine error quotes the failing statement back, and the statement carries the resolved
     * s3:// location the whole module works to keep out of a response. Shown once it is a leak;
     * written here it is a leak that is kept, and readable by everyone in the workspace.
     */
    @Test
    void aFailureThatNamesTheObjectStoreIsNotKept() {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenRunSavedReturnTheRow();

        AnalyticsQueryRun submitted = run();
        submitted.setRunStatus(AnalyticsQueryRun.STATUS_FAILED);
        submitted.setRowCount(null);
        submitted.setErrorMessage("IO Error: No files found that match the pattern "
            + "\"s3://etl-bucket/etl-demo/sales.csv\"");

        this.service.recordRun(submitted);

        String kept = capturedRunSave().getErrorMessage();
        assertThat(kept).doesNotContain("s3://").doesNotContain("etl-bucket");
        assertThat(kept).isNotNull();
    }

    @Test
    void aFailureThatNamesACredentialIsNotKept() {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenRunSavedReturnTheRow();

        AnalyticsQueryRun submitted = run();
        submitted.setRunStatus(AnalyticsQueryRun.STATUS_FAILED);
        submitted.setErrorMessage("Invalid Input Error: secret AKIAI44QH8DHBEXAMPLE was rejected");

        this.service.recordRun(submitted);

        assertThat(capturedRunSave().getErrorMessage()).doesNotContain("AKIAI44QH8DHBEXAMPLE");
    }

    /** The control: a message written for a person survives intact. */
    @Test
    void aFailureWrittenForAPersonIsKeptAsItWas() {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenRunSavedReturnTheRow();

        AnalyticsQueryRun submitted = run();
        submitted.setRunStatus(AnalyticsQueryRun.STATUS_FAILED);
        submitted.setErrorMessage("The dataset could not be read. Check the path.");

        this.service.recordRun(submitted);

        assertThat(capturedRunSave().getErrorMessage())
            .isEqualTo("The dataset could not be read. Check the path.");
    }

    @Test
    void aRunWithNobodySignedInIsNotRecorded() {
        // Scheduled work and Kafka callbacks run with no caller. AuditListener already refuses to
        // invent an author for them; a run row with neither a tenant nor a user would say a read
        // happened without saying whose.
        TenantContext.clear();
        AnalyticsQueryRun submitted = run();

        assertThat(this.service.recordRun(submitted)).isNull();
        verify(this.analyticsQueryRunRepository, never()).save(any(AnalyticsQueryRun.class));
    }

    /**
     * Nothing prunes analytics_query_run, which only works while every read of it is bounded.
     */
    @Test
    void historyIsNeverReadWithoutACeiling() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        when(this.analyticsQueryRunRepository
            .findAllByOrderByDateCreatedDescAnalyticsQueryRunIdDesc(any(Pageable.class)))
            .thenReturn(Collections.emptyList());

        this.service.fetchRecentRuns(null, 1000000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(this.analyticsQueryRunRepository)
            .findAllByOrderByDateCreatedDescAnalyticsQueryRunIdDesc(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isLessThanOrEqualTo(200);
    }

    @Test
    void historyForAnotherTenantsSavedQueryIsRefusedBeforeItIsRead() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenLoadedById(THEIR_QUERY_ID, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID));

        ResponseDto response = this.service.fetchRecentRuns(THEIR_QUERY_ID, 20);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsQueryRunRepository, never())
            .findByAnalyticsQueryIdOrderByDateCreatedDescAnalyticsQueryRunIdDesc(anyLong(), any(Pageable.class));
    }

    /**
     * The listings are the one path a unit test cannot prove through the database: they rely on
     * the Hibernate filter, which really does apply to a query. So the service drops anything it
     * does not own on the way out as well, and this is that second layer -- the repository is
     * made to return a row from the wrong workspace, as it would if the filter were never
     * enabled.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aListingDropsAnyRowTheFilterWouldHaveHiddenAnyway() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsQuery mine = savedQueryOwnedBy(ACME, OWN_QUERY_ID);
        when(this.analyticsQueryRepository.findAllByOrderByAnalyticsQueryIdDesc())
            .thenReturn(Arrays.asList(mine, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID)));

        ResponseDto response = this.service.fetchAllQueries();

        assertThat((List<Object>) response.getData()).containsExactly(mine);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHistoryListingDropsAnyRunTheFilterWouldHaveHiddenAnyway() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsQueryRun mine = run();
        mine.setTenantId(ACME);
        AnalyticsQueryRun theirs = run();
        theirs.setTenantId(GLOBEX);
        when(this.analyticsQueryRunRepository
            .findAllByOrderByDateCreatedDescAnalyticsQueryRunIdDesc(any(Pageable.class)))
            .thenReturn(Arrays.asList(mine, theirs));

        ResponseDto response = this.service.fetchRecentRuns(null, 20);

        assertThat((List<Object>) response.getData()).containsExactly(mine);
    }

    /** Every listing turns the tenant filter on before it reads. */
    @Test
    void everyListingEnablesTheTenantFilterFirst() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        when(this.analyticsQueryRepository.findAllByOrderByAnalyticsQueryIdDesc())
            .thenReturn(Collections.emptyList());

        this.service.fetchAllQueries();

        verify(this.tenantFilterHelper, atLeastOnce()).enableIfNeeded(this.entityManager);
    }

    // ---------------------------------------------------------------- the rows themselves

    @Test
    void neitherTableSaysWhereTheConnectionPoints() {
        assertThat(offendingFieldsOf(AnalyticsQuery.class))
            .as("a saved query stores the connection alias and the path; the bucket is read from "
                + "the connection at run time, and a copy here would be a second source of truth "
                + "for it that a repointed connection would silently leave stale")
            .isEmpty();
        assertThat(offendingFieldsOf(AnalyticsQueryRun.class))
            .as("history describes what was read the same way everything else does, by alias and "
                + "path -- a resolved bucket URL in an audit row is a resolved bucket URL kept "
                + "for as long as the audit is")
            .isEmpty();
        assertThat(fieldNamesOf(AnalyticsQuery.class)).contains("connectionAlias", "datasetPath");
        assertThat(fieldNamesOf(AnalyticsQueryRun.class)).contains("connectionAlias", "datasetPath");
    }

    @Test
    void bothTablesBelongToExactlyOneWorkspace() {
        for (Class<?> entity : Arrays.asList(AnalyticsQuery.class, AnalyticsQueryRun.class)) {
            Filter filter = entity.getAnnotation(Filter.class);
            assertThat(filter).as(entity.getSimpleName()).isNotNull();
            assertThat(filter.name()).isEqualTo("tenantFilter");
            // Deliberately not StorageConnection's "(tenant_id = :tenantId or tenant_id is null)".
            // That form publishes the platform's rows to every tenant, which is right for a
            // catalogue the whole application resolves buckets through and wrong for one person's
            // saved work -- and wronger still for the record of what they read.
            assertThat(filter.condition())
                .as(entity.getSimpleName() + " filter condition")
                .isEqualTo("tenant_id = :tenantId");
        }
    }

    @Test
    void bothRowsAreStampedWithTheirAuthorWithoutTheSavingCodeRememberingTo() {
        for (Class<?> entity : Arrays.asList(AnalyticsQuery.class, AnalyticsQueryRun.class)) {
            EntityListeners listeners = entity.getAnnotation(EntityListeners.class);
            assertThat(listeners).as(entity.getSimpleName()).isNotNull();
            assertThat(listeners.value()).contains(AuditListener.class);
        }

        AnalyticsQueryRun history = new AnalyticsQueryRun();
        TenantContext.set(ACME, "TENANT_USER", ACME_USER, "user@acme.test");
        new AuditListener().onCreate(history);

        assertThat(history.getCreatedBy()).isEqualTo(ACME_USER);
        assertThat(history.getUpdatedBy())
            .as("a run row is written once and never edited; that is what makes it evidence")
            .isNull();
    }

    /**
     * stage and prod run Hibernate with ddl-auto=validate, so a column the changeset does not
     * create is a startup failure there and nothing at all in dev, where ddl-auto=update quietly
     * adds it. This is the cheap version of that check.
     */
    @Test
    void everyMappedColumnIsCreatedByTheChangeset() throws Exception {
        String sql = changesetSql().toLowerCase(Locale.ROOT);

        for (Class<?> entity : Arrays.asList(AnalyticsQuery.class, AnalyticsQueryRun.class)) {
            assertThat(sql).contains(entity.getAnnotation(Table.class).name());

            List<String> missing = new ArrayList<>();
            for (Field field : entity.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || field.getAnnotation(Transient.class) != null) {
                    continue;
                }
                if (!sql.contains(column.name().toLowerCase(Locale.ROOT))) {
                    missing.add(column.name());
                }
                SequenceGenerator generator = field.getAnnotation(SequenceGenerator.class);
                if (generator != null) {
                    // dev's ddl-auto=update would create a sequence starting at 1; prod's
                    // validate would not create one at all.
                    assertThat(sql).contains(generator.sequenceName());
                }
            }
            assertThat(missing)
                .as(entity.getSimpleName() + ": mapped but not in V32__analytics_query.sql -- add "
                    + "a new changeset, never edit the applied one")
                .isEmpty();
        }
    }

    // ---------------------------------------------------------------- the controller

    /**
     * The role floor is the only thing between an authenticated caller of any kind and this API,
     * and an annotation that is deleted, misspelled or shadowed by a method-level one fails no
     * compile and throws nothing at startup.
     */
    @Test
    void theLibraryApiHasTheSameRoleFloorAsTheAnalyticsApi() {
        PreAuthorize classLevel = AnnotatedElementUtils
            .findMergedAnnotation(AnalyticsLibraryRestApi.class, PreAuthorize.class);
        assertThat(classLevel).isNotNull();
        assertThat(classLevel.value()).isEqualTo("hasRole('TENANT_USER')");

        for (Method method : AnalyticsLibraryRestApi.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(PreAuthorize.class))
                .as(method.getName() + " carries its own guard, which REPLACES the class floor "
                    + "rather than adding to it")
                .isNull();
        }
    }

    /**
     * The library cannot become a second door beside the locked one, because it holds nothing
     * that can open one. Stated structurally rather than in a comment: this controller depends on
     * the library service and on nothing that runs SQL.
     */
    @Test
    void theLibraryApiCannotExecuteAnything() {
        for (Constructor<?> constructor : AnalyticsLibraryRestApi.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertThat(parameter.getName())
                    .doesNotContain("AnalyticsQueryService")
                    .doesNotContain("DuckDb")
                    .doesNotContain("DatasetResolver");
            }
        }
        for (Field field : AnalyticsLibraryRestApi.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                .doesNotContain("AnalyticsQueryService")
                .doesNotContain("DuckDb")
                .doesNotContain("DatasetResolver");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void signedInAs(Long tenantId, String role, Long appUserId) {
        TenantContext.set(tenantId, role, appUserId, "user@test");
    }

    /**
     * The repository hands the row back whatever tenant it belongs to, which is what the real one
     * does on a load by id: a Hibernate @Filter is applied to queries and not to a primary-key
     * load. Anything that refuses below is the service refusing.
     */
    private void whenLoadedById(Long analyticsQueryId, AnalyticsQuery row) {
        when(this.analyticsQueryRepository.findByAnalyticsQueryId(analyticsQueryId))
            .thenReturn(Optional.of(row));
    }

    private void whenSavedReturnTheRow() {
        when(this.analyticsQueryRepository.save(any(AnalyticsQuery.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void whenRunSavedReturnTheRow() {
        when(this.analyticsQueryRunRepository.save(any(AnalyticsQueryRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AnalyticsQuery capturedSave() {
        ArgumentCaptor<AnalyticsQuery> saved = ArgumentCaptor.forClass(AnalyticsQuery.class);
        verify(this.analyticsQueryRepository).save(saved.capture());
        return saved.getValue();
    }

    private AnalyticsQueryRun capturedRunSave() {
        ArgumentCaptor<AnalyticsQueryRun> saved = ArgumentCaptor.forClass(AnalyticsQueryRun.class);
        verify(this.analyticsQueryRunRepository).save(saved.capture());
        return saved.getValue();
    }

    private static AnalyticsQuery savedQueryOwnedBy(Long tenantId, Long analyticsQueryId) {
        AnalyticsQuery query = new AnalyticsQuery();
        query.setAnalyticsQueryId(analyticsQueryId);
        query.setTenantId(tenantId);
        query.setQueryName("last night's export");
        query.setConnectionAlias("store");
        query.setDatasetPath("etl-demo/orders/2026/*.csv");
        query.setQueryText("select 1");
        return query;
    }

    private static AnalyticsQuery payload() {
        AnalyticsQuery payload = new AnalyticsQuery();
        payload.setQueryName("sales by month");
        payload.setConnectionAlias("store");
        payload.setDatasetPath("etl-demo/sales.csv");
        payload.setQueryText("select month, sum(total) from dataset group by month");
        return payload;
    }

    private static AnalyticsQueryRun run() {
        AnalyticsQueryRun run = new AnalyticsQueryRun();
        run.setConnectionAlias("store");
        run.setDatasetPath("etl-demo/sales.csv");
        run.setQueryText("select count(*) from dataset");
        run.setRunStatus(AnalyticsQueryRun.STATUS_SUCCESS);
        run.setRowCount(42L);
        run.setDurationMs(310L);
        return run;
    }

    private static List<String> offendingFieldsOf(Class<?> entity) {
        List<String> offenders = new ArrayList<>();
        for (Field field : entity.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            for (String word : LOCATION_AND_CREDENTIAL_WORDS) {
                if (name.contains(word)) {
                    offenders.add(entity.getSimpleName() + "." + field.getName());
                }
            }
        }
        return offenders;
    }

    private static List<String> fieldNamesOf(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Field field : entity.getDeclaredFields()) {
            names.add(field.getName());
        }
        return names;
    }

    private static String changesetSql() throws Exception {
        // Surefire runs from the module root; the second path is for a run from the parent.
        for (String prefix : new String[] { "", "process/" }) {
            File file = new File(prefix + "src/main/resources/db/changelog/changelog-sets/"
                + "V32.0-analytics-query/V32__analytics_query.sql");
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not find V32__analytics_query.sql from "
            + System.getProperty("user.dir"));
    }

    @Test
    void deletingASavedQueryNamesTheDashboardTilesItTakesWithIt() throws Exception {
        // V34 added ON DELETE CASCADE from widget to saved query, which silently changed what
        // this endpoint does: deleting a query you no longer wanted also removed every dashboard
        // tile showing it, and the message still said only "Saved query deleted". A dashboard
        // losing a tile with no explanation is indistinguishable from a bug.
        TenantContext.set(ACME, "TENANT_USER", ACME_USER, "analyst");
        AnalyticsQuery mine = savedQueryOwnedBy(ACME, 42L);
        when(this.analyticsQueryRepository.findByAnalyticsQueryId(42L))
            .thenReturn(Optional.of(mine));
        when(this.analyticsDashboardWidgetRepository.findByAnalyticsQueryId(42L))
            .thenReturn(java.util.Arrays.asList(new AnalyticsDashboardWidget(),
                new AnalyticsDashboardWidget()));

        ResponseDto response = this.service.deleteQuery(42L);

        assertThat(response.getMessage()).contains("2 dashboard widget(s)");
        // Removed explicitly, not left to a cascade that does not fire for a platform admin: the
        // composite key includes tenant_id, and Postgres does not enforce a foreign key when a
        // column in it is null.
        verify(this.analyticsDashboardWidgetRepository).deleteAll(anyList());
    }

    @Test
    void deletingASavedQueryNothingShowsSaysNothingExtra() throws Exception {
        // The control. A message that always warned about dashboards would pass the test above
        // while crying wolf on every delete.
        TenantContext.set(ACME, "TENANT_USER", ACME_USER, "analyst");
        when(this.analyticsQueryRepository.findByAnalyticsQueryId(43L))
            .thenReturn(Optional.of(savedQueryOwnedBy(ACME, 43L)));
        when(this.analyticsDashboardWidgetRepository.findByAnalyticsQueryId(43L))
            .thenReturn(java.util.Collections.emptyList());

        ResponseDto response = this.service.deleteQuery(43L);

        assertThat(response.getMessage()).doesNotContain("widget");
        verify(this.analyticsDashboardWidgetRepository, never()).deleteAll(anyList());
    }

}
