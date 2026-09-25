package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.TestIdentity;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import process.model.pojo.UserPageAccess;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.repository.UserPageAccessRepository;
import process.model.service.PageAccessService;
import process.model.service.impl.AppUserServiceImpl;
import process.notifications.TestNotifications;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TokenRevocations;
import process.storage.TrustedStorageOperations;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * MIG-13 (DEF-012, P5) against a real Postgres: the Hibernate tenant filter on app_user and
 * user_page_access, the reads that are deliberately outside it, and V65's tenant_id on
 * user_page_access.
 *
 * The filter is defence in depth. The hand-written guards -- listUsers' tenant query, scopedFind,
 * refusalFor -- keep their semantics and are exercised here with the filter on. Opt-in, like every
 * IdentityPostgres test.
 */
class IdentityTenantFilterPostgresTest {

    private static final long A = 5001L;
    private static final long B = 5002L;
    private static final long PLATFORM_ADMIN = 9000L;
    private static final long ADMIN_A = 9001L;
    private static final long USER_A = 9002L;
    private static final long USER_B = 9003L;

    private static IdentityPostgres db;
    private static AppUserRepository users;
    private static UserPageAccessRepository exceptions;
    private final TenantFilterHelper filter = new TenantFilterHelper();

    @BeforeAll
    static void database() throws Exception {
        db = IdentityPostgres.create("mig13_filter").migrate().withJpa();
        JdbcTemplate sql = db.sql();
        tenant(sql, A, "a");
        tenant(sql, B, "b");
        user(sql, PLATFORM_ADMIN, null, "PLATFORM_ADMIN", "root@platform.example");
        user(sql, ADMIN_A, A, "TENANT_ADMIN", "admin@a.example");
        user(sql, USER_A, A, "TENANT_USER", "olivia@a.example");
        user(sql, USER_B, B, "TENANT_USER", "brian@b.example");
        sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) VALUES (?, ?, 'jobs', false)", USER_A, A);
        sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) VALUES (?, ?, 'reports', true)", USER_B, B);
        users = db.repository(AppUserRepository.class);
        exceptions = db.repository(UserPageAccessRepository.class);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    static void tenant(JdbcTemplate sql, long id, String code) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", id, code, code);
    }

    static void user(JdbcTemplate sql, long id, Long tenantId, String role, String username) {
        sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username) "
            + "VALUES (?, ?, ?, 'x', 'Active', ?, ?)", id, tenantId, "Name " + id, role, username);
    }

    private <T> T filteredAs(Long tenantId, String role, Long appUserId, Supplier<T> read) {
        TenantContext.set(tenantId, role, appUserId, "caller");
        return db.transaction().execute(tx -> {
            this.filter.enableIfNeeded(db.entityManager());
            return read.get();
        });
    }

    private static List<Long> ids(List<AppUser> rows) {
        return rows.stream().map(AppUser::getAppUserId).sorted().collect(Collectors.toList());
    }

    private static List<Long> owners(List<UserPageAccess> rows) {
        return rows.stream().map(UserPageAccess::getAppUserId).sorted().collect(Collectors.toList());
    }

    // -- the filter ---------------------------------------------------------------------

    @Test
    void aNakedFindAllSeesOnlyTheCallersTenant() {
        assertThat(ids(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A, () -> users.findAll()))).containsExactly(ADMIN_A, USER_A);
        assertThat(owners(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A, () -> exceptions.findAll()))).containsExactly(USER_A);
        assertThat(ids(this.filteredAs(B, "TENANT_USER", USER_B, () -> users.findAll()))).containsExactly(USER_B);
        assertThat(owners(this.filteredAs(B, "TENANT_USER", USER_B, () -> exceptions.findAll()))).containsExactly(USER_B);
    }

    @Test
    void aDerivedQueryNamingAnotherTenantStillReturnsNothing() {
        assertThat(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A,
            () -> users.findByTenantIdAndStatusNotOrderByAppUserIdDesc(B, Status.Delete))).isEmpty();
        assertThat(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A,
            () -> exceptions.findByIdAppUserIdIn(Arrays.asList(USER_A, USER_B)))).extracting(UserPageAccess::getAppUserId)
            .containsExactly(USER_A);
    }

    @Test
    void aPlatformAdminIsExplicitlyUnfilteredAndSeesEveryTenant() {
        assertThat(ids(this.filteredAs(null, "PLATFORM_ADMIN", PLATFORM_ADMIN, () -> users.findAll())))
            .containsExactly(PLATFORM_ADMIN, ADMIN_A, USER_A, USER_B);
        assertThat(owners(this.filteredAs(null, "PLATFORM_ADMIN", PLATFORM_ADMIN, () -> exceptions.findAll())))
            .containsExactly(USER_A, USER_B);
    }

    @Test
    void aTenantlessNonAdminSeesNobody() {
        assertThat(this.filteredAs(null, "TENANT_ADMIN", 1L, () -> users.findAll())).isEmpty();
        assertThat(this.filteredAs(null, "TENANT_USER", 1L, () -> exceptions.findAll())).isEmpty();
    }

    // -- what the filter deliberately does not reach ------------------------------------

    @Test
    void theReadsThatCrossTenantsStillCrossThemWithTheFilterOn() {
        assertThat(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A, () -> users.findLiveByUsernameAcrossTenants("BRIAN@b.example")))
            .as("sign-in").isPresent();
        assertThat(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A, () -> users.isUsernameTakenAcrossTenants("brian@b.example")))
            .as("is the name taken").isTrue();
        assertThat(ids(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A,
            () -> users.findAllByIdAcrossTenants(Arrays.asList(PLATFORM_ADMIN, USER_B))))).as("names")
            .containsExactly(PLATFORM_ADMIN, USER_B);
        Map<Long, String> names = this.filteredAs(A, "TENANT_ADMIN", ADMIN_A,
            () -> new UserNameResolver(TestIdentity.over(users, null)).namesFor(Arrays.asList(PLATFORM_ADMIN, USER_A)));
        assertThat(names).as("a platform admin who created a row in A still has a name there")
            .containsEntry(PLATFORM_ADMIN, "Name 9000").containsEntry(USER_A, "Name 9002");
    }

    /**
     * Hibernate filters reach queries, not loads by id: findById returns another tenant's row with the
     * filter on. That is why scopedFind's hand-written check stays the guard for every by-id write.
     */
    @Test
    void findByIdIsNotFilteredSoTheHandWrittenGuardsStillDecide() throws Exception {
        assertThat(this.filteredAs(A, "TENANT_ADMIN", ADMIN_A, () -> users.findById(USER_B))).isPresent();

        AppUserServiceImpl service = new AppUserServiceImpl(users, db.repository(TenantRepository.class),
            mock(PasswordEncoder.class), TestNotifications.recording(null, mock(TestNotifications.NoticeSink.class),
                mock(TestNotifications.MailSink.class)), new UserNameResolver(TestIdentity.over(users, null)), mock(TrustedStorageOperations.class),
            mock(PageAccessService.class), this.filter, mock(TokenRevocations.class));
        ReflectionTestUtils.setField(service, "entityManager", db.entityManager());
        AppUserDto reset = new AppUserDto();
        reset.setAppUserId(USER_B);
        reset.setPassword("Passw0rd!");

        ResponseDto refused = this.filteredAs(A, "TENANT_ADMIN", ADMIN_A, () -> {
            try {
                return service.resetPassword(reset);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(refused.getMessage()).isEqualTo("User not found.");

        TenantContext.set(A, "TENANT_ADMIN", ADMIN_A, "admin@a.example");
        ResponseDto listed = db.transaction().execute(tx -> {
            try {
                return service.listUsers();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat((List<?>) listed.getData()).hasSize(2);
    }

    // -- V65 ----------------------------------------------------------------------------

    @Test
    void v64BackfillsEachExceptionsTenantFromItsPerson() throws Exception {
        try (IdentityPostgres before = IdentityPostgres.create("mig13_backfill")
                .migrateBefore("db/changelog/yaml/V65.0-user-page-access-tenant.yaml")) {
            JdbcTemplate sql = before.sql();
            tenant(sql, A, "a");
            user(sql, USER_A, A, "TENANT_USER", "olivia@a.example");
            sql.update("INSERT INTO user_page_access (app_user_id, page_key, allowed) VALUES (?, 'jobs', false)", USER_A);

            before.migrate();

            assertThat(sql.queryForObject("SELECT tenant_id FROM user_page_access WHERE app_user_id = ?", Long.class, USER_A))
                .isEqualTo(A);
            assertThat(sql.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'user_page_access' AND column_name = 'tenant_id'", String.class)).isEqualTo("NO");
        }
    }

    @Test
    void v64HaltsOnAnExceptionHeldByAPersonWithNoTenant() throws Exception {
        try (IdentityPostgres before = IdentityPostgres.create("mig13_halt")
                .migrateBefore("db/changelog/yaml/V65.0-user-page-access-tenant.yaml")) {
            JdbcTemplate sql = before.sql();
            user(sql, PLATFORM_ADMIN, null, "PLATFORM_ADMIN", "root@platform.example");
            sql.update("INSERT INTO user_page_access (app_user_id, page_key, allowed) VALUES (?, 'jobs', true)", PLATFORM_ADMIN);

            assertThatThrownBy(before::migrate).hasStackTraceContaining("V65 cannot give user_page_access a tenant");
        }
    }

    /** A person moved to another tenant takes their exceptions along; one left with no tenant cannot keep any. */
    @Test
    void anExceptionFollowsItsPersonsTenantAndCannotOutliveIt() throws Exception {
        try (IdentityPostgres fresh = IdentityPostgres.create("mig13_cascade").migrate()) {
            JdbcTemplate sql = fresh.sql();
            tenant(sql, A, "a");
            tenant(sql, B, "b");
            user(sql, USER_A, A, "TENANT_USER", "olivia@a.example");
            sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) VALUES (?, ?, 'jobs', false)", USER_A, A);

            sql.update("UPDATE app_user SET tenant_id = ? WHERE app_user_id = ?", B, USER_A);
            assertThat(sql.queryForObject("SELECT tenant_id FROM user_page_access WHERE app_user_id = ?", Long.class, USER_A))
                .isEqualTo(B);

            assertThatThrownBy(() -> sql.update("UPDATE app_user SET tenant_id = NULL WHERE app_user_id = ?", USER_A))
                .hasMessageContaining("tenant_id");
            assertThatThrownBy(() -> sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) "
                + "VALUES (?, ?, 'reports', true)", USER_A, A)).as("an exception naming a tenant its person is not in")
                .hasMessageContaining("fk_user_page_access_user_tenant");
        }
    }
}
