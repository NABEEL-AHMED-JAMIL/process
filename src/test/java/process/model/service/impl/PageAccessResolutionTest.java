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
import process.model.repository.TenantRepository;
import process.model.repository.UserPageAccessRepository;
import process.model.service.NotificationCenterService;
import process.security.PageAccessCache;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.mockito.Mockito;
import process.model.pojo.UserPageAccess;
import process.model.service.PageAccessService;

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
    @Mock private TenantRepository tenantRepository;
    @Mock private UserPageAccessRepository exceptionRepository;

    private PageAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new PageAccessServiceImpl(this.profileRepository, this.appUserRepository,
            this.notificationCenterService, this.userNameResolver, new PageAccessCache(), this.tenantRepository,
            this.exceptionRepository);
        lenient().when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(this.exceptionRepository.findByIdAppUserId(any())).thenReturn(Collections.emptyList());
        lenient().when(this.exceptionRepository.findByIdAppUserIdIn(any())).thenReturn(Collections.emptyList());
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
            .thenReturn(Optional.of(profile(600L, TENANT_B, Status.Active, "analytics", "ai-prompts")));
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

    /** The user list asks for every name at once; nulls and unknown ids fall out quietly. */
    @Test
    void profileNamesAreResolvedInOneReadForAWholeList() {
        PageAccessProfile a = profile(500L, TENANT_A, Status.Active, "jobs"); a.setProfileName("Operator");
        PageAccessProfile gone = profile(501L, TENANT_A, Status.Delete, "jobs"); gone.setProfileName("Old");
        when(this.profileRepository.findAllById(any())).thenReturn(Arrays.asList(a, gone));

        Map<Long, String> names = this.service.profileNamesFor(Arrays.asList(500L, null, 501L, 500L));

        assertThat(names).containsExactly(Assertions.entry(500L, "Operator"));
        assertThat(this.service.profileNamesFor(Arrays.asList((Long) null))).isEmpty();
        assertThat(this.service.profileNamesFor(null)).isEmpty();
    }

    /** Exceptions sit on top of the profile: an allowed one opens, a withheld one closes. */
    @Test
    void exceptionsAdjustTheProfileInBothDirections() {
        when(this.profileRepository.findById(500L))
            .thenReturn(Optional.of(profile(500L, TENANT_A, Status.Active, "jobs", "queue")));
        when(this.exceptionRepository.findByIdAppUserId(7L)).thenReturn(Arrays.asList(
            new UserPageAccess(7L, "reports", true, 9L),
            new UserPageAccess(7L, "queue", false, 9L),
            new UserPageAccess(7L, "page-that-was-removed", true, 9L)));

        assertThat(this.service.effectivePages(user(UserRole.TENANT_USER, TENANT_A, 500L)))
            .containsExactlyInAnyOrder(PageKey.JOBS, PageKey.REPORTS);
    }

    /** An admin is answered before exceptions are even read. */
    @Test
    void exceptionsNeverTouchAnAdmin() {
        assertThat(this.service.effectivePages(user(UserRole.TENANT_ADMIN, TENANT_A, null))).isEqualTo(PageKey.all());
        Mockito.verify(this.exceptionRepository, Mockito.never()).findByIdAppUserId(any());
    }

    /** The user list's summary: three reads for the whole page, admins read as everything. */
    @Test
    void accessSummaryCoversAListInThreeReads() {
        PageAccessProfile operator = profile(500L, TENANT_A, Status.Active, "jobs", "queue"); operator.setProfileName("Operator");
        when(this.profileRepository.findAllById(any())).thenReturn(Arrays.asList(operator));
        when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(TENANT_A, Status.Active)).thenReturn(Optional.of(operator));
        AppUser olivia = user(UserRole.TENANT_USER, TENANT_A, 500L); olivia.setAppUserId(44L);
        AppUser ava = user(UserRole.TENANT_USER, TENANT_A, null); ava.setAppUserId(45L);
        AppUser daniel = user(UserRole.TENANT_ADMIN, TENANT_A, null); daniel.setAppUserId(9L);
        when(this.exceptionRepository.findByIdAppUserIdIn(any())).thenReturn(Arrays.asList(
            new UserPageAccess(44L, "reports", true, 9L)));

        Map<Long, PageAccessService.AccessSummary> summary =
            this.service.accessSummaryFor(Arrays.asList(olivia, ava, daniel));

        assertThat(summary.get(44L).profileName).isEqualTo("Operator");
        assertThat(summary.get(44L).pageCount).isEqualTo(3);
        assertThat(summary.get(44L).exceptionCount).isEqualTo(1);
        assertThat(summary.get(45L).profileName).isNull();
        assertThat(summary.get(45L).defaultProfileName).isEqualTo("Operator");
        assertThat(summary.get(45L).pageCount).isEqualTo(2);
        assertThat(summary.get(9L).pageCount).isEqualTo(PageKey.values().length);
        Mockito.verify(this.profileRepository, Mockito.never()).findById(any());
    }
}
