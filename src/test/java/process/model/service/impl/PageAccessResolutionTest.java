package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.PageKey;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.PageAccessProfile;
import process.model.repository.AppUserRepository;
import process.model.repository.PageAccessProfileRepository;
import process.model.service.NotificationCenterService;
import process.security.PageAccessCache;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Who may open what: the one rule, from the top.
 *
 * Admins are never subject to a profile. A tenant user gets their own profile, else the
 * workspace default, else everything -- and "everything" is what keeps a workspace that never
 * touched the feature exactly as it was.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class PageAccessResolutionTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;

    @Mock private PageAccessProfileRepository profileRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private NotificationCenterService notificationCenterService;
    @Mock private UserNameResolver userNameResolver;

    private PageAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new PageAccessServiceImpl(this.profileRepository, this.appUserRepository,
            this.notificationCenterService, this.userNameResolver, new PageAccessCache());
        lenient().when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(any(), any()))
            .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AppUser user(UserRole role, Long tenantId, Long profileId) {
        AppUser u = new AppUser();
        u.setAppUserId(7L);
        u.setUserRole(role);
        u.setTenantId(tenantId);
        u.setPageAccessProfileId(profileId);
        u.setStatus(Status.Active);
        return u;
    }

    private PageAccessProfile profile(long id, Long tenantId, Status status, String... keys) {
        PageAccessProfile p = new PageAccessProfile();
        p.setPageAccessProfileId(id);
        p.setTenantId(tenantId);
        p.setStatus(status);
        p.setPageKeys(new HashSet<>(Arrays.asList(keys)));
        return p;
    }

    @Test
    void anAdminOpensEverythingWhateverTheRowSays() {
        assertThat(this.service.effectivePages(user(UserRole.TENANT_ADMIN, TENANT_A, 500L))).isEqualTo(PageKey.all());
        assertThat(this.service.effectivePages(user(UserRole.PLATFORM_ADMIN, null, 500L))).isEqualTo(PageKey.all());
    }

    @Test
    void aTenantUserWithNoProfileAndNoDefaultKeepsEverything() {
        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, null))).isEqualTo(PageKey.all());
    }

    @Test
    void aTenantUserGetsTheirOwnProfile() {
        when(this.profileRepository.findById(500L))
            .thenReturn(Optional.of(profile(500L, TENANT_A, Status.Active, "jobs", "queue")));

        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, 500L)))
            .containsExactlyInAnyOrder(PageKey.JOBS, PageKey.QUEUE);
    }

    @Test
    void anUnassignedTenantUserGetsTheWorkspaceDefault() {
        when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.of(profile(501L, TENANT_A, Status.Active, "reports")));

        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, null)))
            .containsExactly(PageKey.REPORTS);
    }

    /** A profile row from another workspace on the user grants nothing from that workspace. */
    @Test
    void aProfileFromAnotherWorkspaceIsIgnoredInFavourOfTheDefault() {
        when(this.profileRepository.findById(600L))
            .thenReturn(Optional.of(profile(600L, TENANT_B, Status.Active, "analytics", "ai-agents")));
        when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.of(profile(501L, TENANT_A, Status.Active, "jobs")));

        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, 600L)))
            .containsExactly(PageKey.JOBS);
    }

    @Test
    void aDeletedProfileReadsAsNoneAndAnUnknownKeyIsSkipped() {
        when(this.profileRepository.findById(500L))
            .thenReturn(Optional.of(profile(500L, TENANT_A, Status.Delete, "jobs")));
        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, 500L))).isEqualTo(PageKey.all());

        when(this.profileRepository.findById(502L))
            .thenReturn(Optional.of(profile(502L, TENANT_A, Status.Active, "jobs", "page-that-was-removed")));
        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, 502L)))
            .containsExactly(PageKey.JOBS);
    }

    /** An empty profile is a real answer: dashboard only. It must not fall through to "all". */
    @Test
    void anEmptyProfileOpensNothingBeyondTheDashboard() {
        when(this.profileRepository.findById(503L))
            .thenReturn(Optional.of(profile(503L, TENANT_A, Status.Active)));

        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, 503L))).isEmpty();
    }
}
