package process.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.TestIdentity;
import process.api.AppUserRestApi;
import process.api.PageAccessRestApi;
import process.model.dto.AppUserDto;
import process.model.dto.PageAccessProfileDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.repository.AppUserRepository;
import process.model.repository.PageAccessProfileRepository;
import process.model.repository.TenantRepository;
import process.model.repository.UserPageAccessRepository;
import process.model.service.impl.AppUserServiceImpl;
import process.model.service.impl.PageAccessServiceImpl;
import process.notifications.TestNotifications;
import process.security.PageAccessCache;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TokenRevocations;
import process.storage.TrustedStorageOperations;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-92's CI cross-tenant probe: every Identity endpoint a tenant can call, called by tenant A's
 * administrator and user with tenant B's ids, against a real database with the tenant filter on.
 *
 * A probe passes when nothing of B's comes back -- no name, address, profile or page -- and nothing of
 * B's changed. Which endpoints must be probed is not left to memory: IdentityProbeCoverageTest fails
 * when a controller method is neither probed here nor platform-admin only. The same probe runs against
 * identity-service once Identity has moved (MIG-108). Opt-in, like every IdentityPostgres test.
 */
class IdentityCrossTenantProbePostgresTest {

    static final long A = 5001L;
    static final long B = 5002L;
    static final long ADMIN_A = 9001L;
    static final long USER_A = 9002L;
    static final long ADMIN_B = 9101L;
    static final long USER_B = 9102L;
    static final long PROFILE_A = 7001L;
    static final long PROFILE_B = 7101L;

    /** What of B's must never reach A: its people's addresses and names, its profile, its name. */
    static final List<String> B_MARKERS = Arrays.asList("bravo.example", "Bella Bravo", "Brian Bravo", "B-Secret-Profile",
        "Bravo Workspace");

    private static IdentityPostgres db;
    private static AppUserRestApi users;
    private static PageAccessRestApi pages;
    private static TestNotifications.NoticeSink notices;
    private static TrustedStorageOperations storage;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> leaks = new ArrayList<>();

    @BeforeAll
    static void database() throws Exception {
        db = IdentityPostgres.create("mig92_probe").migrate().withJpa();
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'acme', 'Acme Workspace')", A);
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'bravo', 'Bravo Workspace')", B);
        sql.update("INSERT INTO page_access_profile (page_access_profile_id, tenant_id, profile_name, is_default, status) VALUES (?, ?, 'Operators', true, 'Active')", PROFILE_A, A);
        sql.update("INSERT INTO page_access_profile (page_access_profile_id, tenant_id, profile_name, is_default, status) VALUES (?, ?, 'B-Secret-Profile', true, 'Active')", PROFILE_B, B);
        sql.update("INSERT INTO page_access_profile_page (page_access_profile_id, page_key) VALUES (?, 'jobs'), (?, 'reports')", PROFILE_A, PROFILE_B);
        user(sql, 9000L, null, "PLATFORM_ADMIN", "root@platform.example", "Root Admin", null);
        user(sql, ADMIN_A, A, "TENANT_ADMIN", "alice@acme.example", "Alice Acme", null);
        user(sql, USER_A, A, "TENANT_USER", "adam@acme.example", "Adam Acme", PROFILE_A);
        user(sql, ADMIN_B, B, "TENANT_ADMIN", "brian@bravo.example", "Brian Bravo", null);
        user(sql, USER_B, B, "TENANT_USER", "bella@bravo.example", "Bella Bravo", PROFILE_B);
        sql.update("UPDATE app_user SET avatar_bucket = 'etl-avatar', avatar_key = '9102/profile/avatar.png' WHERE app_user_id = ?", USER_B);
        sql.update("INSERT INTO user_page_access (app_user_id, tenant_id, page_key, allowed) VALUES (?, ?, 'queue', true)", USER_B, B);

        AppUserRepository appUsers = db.repository(AppUserRepository.class);
        TenantRepository tenants = db.repository(TenantRepository.class);
        notices = mock(TestNotifications.NoticeSink.class);
        storage = mock(TrustedStorageOperations.class);
        TenantFilterHelper filter = new TenantFilterHelper();
        PageAccessServiceImpl pageAccess = new PageAccessServiceImpl(db.repository(PageAccessProfileRepository.class), appUsers,
            TestNotifications.recording(null, notices, null), new UserNameResolver(TestIdentity.over(appUsers, tenants)), new PageAccessCache(),
            tenants, db.repository(UserPageAccessRepository.class));
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hash");
        when(encoder.matches(any(), any())).thenReturn(true);
        AppUserServiceImpl appUserService = new AppUserServiceImpl(appUsers, tenants, encoder,
            TestNotifications.recording(null, notices, mock(TestNotifications.MailSink.class)), new UserNameResolver(TestIdentity.over(appUsers, tenants)),
            storage, pageAccess, filter, mock(TokenRevocations.class));
        ReflectionTestUtils.setField(appUserService, "entityManager", db.entityManager());
        ReflectionTestUtils.setField(appUserService, "consoleUrl", "http://localhost:4400");
        users = new AppUserRestApi(appUserService);
        pages = new PageAccessRestApi(pageAccess);
    }

    private static void user(JdbcTemplate sql, long id, Long tenant, String role, String username, String name, Long profile) {
        sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username, page_access_profile_id) "
            + "VALUES (?, ?, ?, 'hash', 'Active', ?, ?, ?)", id, tenant, name, role, username, profile);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** B's rows, as one string, to compare before and after. */
    private static String bSnapshot() {
        JdbcTemplate sql = db.sql();
        return sql.queryForList("SELECT app_user_id, tenant_id, full_name, status, user_role, password, page_access_profile_id, "
            + "token_version FROM app_user WHERE tenant_id = ? ORDER BY 1", B).toString()
            + sql.queryForList("SELECT * FROM page_access_profile WHERE tenant_id = ? ORDER BY 1", B)
            + sql.queryForList("SELECT * FROM page_access_profile_page WHERE page_access_profile_id = ? ORDER BY 2", PROFILE_B)
            + sql.queryForList("SELECT app_user_id, page_key, allowed FROM user_page_access WHERE tenant_id = ? ORDER BY 2", B);
    }

    /** One endpoint call as the caller, in a transaction with the filter as a request has it; records any of B's it hands back. */
    private void probe(String endpoint, long caller, Supplier<ResponseEntity<?>> call) {
        boolean admin = caller == ADMIN_A;
        TenantContext.set(A, admin ? "TENANT_ADMIN" : "TENANT_USER", caller, admin ? "alice@acme.example" : "adam@acme.example");
        ResponseEntity<?> answer = db.transaction().execute(tx -> call.get());
        String body;
        try {
            body = answer.getBody() == null ? "" : JSON.writeValueAsString(answer.getBody());
        } catch (Exception unreadable) {
            body = String.valueOf(answer.getBody());
        }
        for (String marker : B_MARKERS) {
            if (body.contains(marker)) {
                this.leaks.add(endpoint + " as " + caller + " returned " + marker + ": " + body);
            }
        }
        TenantContext.clear();
    }

    private static AppUserDto aimedAt(long id) {
        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(id);
        dto.setFullName("Renamed By Acme");
        dto.setUserRole(UserRole.TENANT_USER);
        dto.setStatus(Status.Inactive);
        dto.setPassword("Passw0rd!");
        dto.setTenantId(B);
        dto.setPageAccessProfileId(PROFILE_B);
        return dto;
    }

    @Test
    void noIdentityEndpointHandsTenantAAnythingOfTenantBsOrChangesIt() throws Exception {
        String before = bSnapshot();

        // /appUser.json -- the tenant admin's screen
        this.probe("appUser.json/listUsers", ADMIN_A, users::listUsers);
        this.probe("appUser.json/updateUser", ADMIN_A, () -> users.updateUser(aimedAt(USER_B)));
        this.probe("appUser.json/changeUserStatus", ADMIN_A, () -> users.changeUserStatus(aimedAt(USER_B)));
        this.probe("appUser.json/resetPassword", ADMIN_A, () -> users.resetPassword(aimedAt(ADMIN_B)));
        this.probe("appUser.json/avatar", ADMIN_A, () -> users.avatar(USER_B));
        AppUserDto intoB = aimedAt(0L);
        intoB.setAppUserId(null);
        intoB.setUsername("new@acme.example");
        intoB.setPassword(null);
        intoB.setPageAccessProfileId(null);
        this.probe("appUser.json/addUser", ADMIN_A, () -> users.addUser(intoB));
        AppUserDto withTheirProfile = aimedAt(0L);
        withTheirProfile.setAppUserId(null);
        withTheirProfile.setUsername("other@acme.example");
        withTheirProfile.setPassword(null);
        this.probe("appUser.json/addUser(foreign profile)", ADMIN_A, () -> users.addUser(withTheirProfile));
        // /appUser.json -- a person's own screen: B's id in the body must not redirect it
        this.probe("appUser.json/me", USER_A, users::currentUser);
        this.probe("appUser.json/updateOwnProfile", USER_A, () -> users.updateOwnProfile(aimedAt(USER_B)));
        this.probe("appUser.json/changeOwnPassword", USER_A, () -> users.changeOwnPassword(
            new LinkedHashMap<>(Collections.singletonMap("newPassword", "Another1!"))));
        AppUserDto theirPicture = aimedAt(USER_B);
        theirPicture.setAvatarKey("9102/profile/avatar.png");
        theirPicture.setAvatarBucket("etl-avatar");
        this.probe("appUser.json/updateOwnAvatar", USER_A, () -> users.updateOwnAvatar(theirPicture));

        // /pageAccess.json
        this.probe("pageAccess.json/pages", USER_A, pages::pages);
        this.probe("pageAccess.json/mine", USER_A, pages::mine);
        this.probe("pageAccess.json/requestAccess", USER_A, () -> pages.requestAccess("reports"));
        this.probe("pageAccess.json/listProfiles", ADMIN_A, () -> pages.listProfiles(B));
        this.probe("pageAccess.json/listPeople", ADMIN_A, () -> pages.listPeople(B));
        PageAccessProfileDto profile = new PageAccessProfileDto();
        profile.setPageAccessProfileId(PROFILE_B);
        profile.setTenantId(B);
        profile.setProfileName("Taken Over");
        profile.setPageKeys(Arrays.asList("jobs"));
        this.probe("pageAccess.json/updateProfile", ADMIN_A, () -> pages.updateProfile(profile));
        PageAccessProfileDto created = new PageAccessProfileDto();
        created.setTenantId(B);
        created.setProfileName("Planted In B");
        created.setPageKeys(Arrays.asList("jobs"));
        this.probe("pageAccess.json/addProfile", ADMIN_A, () -> pages.addProfile(created));
        this.probe("pageAccess.json/deleteProfile", ADMIN_A, () -> pages.deleteProfile(PROFILE_B));
        this.probe("pageAccess.json/setDefaultProfile", ADMIN_A, () -> pages.setDefaultProfile(PROFILE_B));
        this.probe("pageAccess.json/assignProfile", ADMIN_A, () -> pages.assignProfile(USER_B, PROFILE_A));
        this.probe("pageAccess.json/assignProfile(foreign profile)", ADMIN_A, () -> pages.assignProfile(USER_A, PROFILE_B));
        this.probe("pageAccess.json/setPageAccess", ADMIN_A, () -> pages.setPageAccess(USER_B, "analytics", true));
        this.probe("pageAccess.json/clearPageAccess", ADMIN_A, () -> pages.clearPageAccess(USER_B));

        assertThat(this.leaks).isEmpty();
        assertThat(bSnapshot()).as("nothing of B's changed").isEqualTo(before);
        JdbcTemplate sql = db.sql();
        assertThat(sql.queryForObject("SELECT tenant_id FROM app_user WHERE username = 'new@acme.example'", Long.class))
            .as("a user an A admin adds lands in A, whatever tenant the form names").isEqualTo(A);
        assertThat(sql.queryForObject("SELECT count(*) FROM app_user WHERE username = 'other@acme.example'", Integer.class))
            .as("B's profile cannot be handed to A's new user").isZero();
        assertThat(sql.queryForObject("SELECT tenant_id FROM page_access_profile WHERE profile_name = 'Planted In B'", Long.class))
            .isEqualTo(A);
        verify(storage, never()).readForWorkflow(any(), eq("etl-avatar"), eq("9102/profile/avatar.png"));
        verify(notices, never()).create(eq(B), anyLong(), any(), any(), any(), any(), any());
    }

    /** The two null-equals-null cases on record: a tenantless caller owns no platform row and sees no platform picture. */
    @Test
    void aCallerWithNoTenantOwnsNothingThePlatformOwns() throws Exception {
        db.sql().update("UPDATE app_user SET avatar_bucket = 'etl-avatar', avatar_key = '9000/profile/avatar.png' WHERE app_user_id = 9000");
        TenantContext.set(null, "TENANT_ADMIN", 9999L, "orphan@nowhere.example");
        try {
            ResponseEntity<?> picture = db.transaction().execute(tx -> users.avatar(9000L));
            ResponseEntity<?> listed = db.transaction().execute(tx -> users.listUsers());
            assertThat(picture.getStatusCodeValue()).isEqualTo(404);
            assertThat(JSON.writeValueAsString(listed.getBody())).doesNotContain("root@platform.example").doesNotContain("acme.example")
                .doesNotContain("bravo.example");
            verify(storage, never()).readForWorkflow(any(), eq("etl-avatar"), eq("9000/profile/avatar.png"));
        } finally {
            TenantContext.clear();
        }
    }
}
