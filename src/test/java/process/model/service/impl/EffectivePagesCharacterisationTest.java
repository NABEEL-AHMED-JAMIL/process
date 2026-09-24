package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import process.notifications.TestNotifications;
import process.security.PageAccessCache;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-91: effectivePages' three steps -- own profile, workspace default, everything -- at the edges
 * PageAccessResolutionTest leaves open. The chosen profile must be Active, carry a tenant, and carry
 * the user's tenant; failing any of the three falls through to the default, and "everything" is the
 * terminal fallback, which is what makes every fall-through worth pinning.
 */
@ExtendWith(MockitoExtension.class)
class EffectivePagesCharacterisationTest {

    private static final long TENANT_A = 1001L;

    @Mock private PageAccessProfileRepository profileRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private TestNotifications.NoticeSink notices;
    @Mock private UserNameResolver userNameResolver;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserPageAccessRepository exceptionRepository;

    private PageAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new PageAccessServiceImpl(this.profileRepository, this.appUserRepository,
            TestNotifications.recording(null, null, this.notices, null), this.userNameResolver, new PageAccessCache(),
            this.tenantRepository, this.exceptionRepository);
        lenient().when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.of(this.profile(1L, TENANT_A, Status.Active, "reports")));
        lenient().when(this.exceptionRepository.findByIdAppUserId(any())).thenReturn(Collections.emptyList());
    }

    private AppUser tenantUser(Long tenantId, Long profileId) {
        AppUser user = new AppUser();
        user.setAppUserId(7L);
        user.setUserRole(UserRole.TENANT_USER);
        user.setTenantId(tenantId);
        user.setPageAccessProfileId(profileId);
        user.setStatus(Status.Active);
        return user;
    }

    private PageAccessProfile profile(long id, Long tenantId, Status status, String... keys) {
        PageAccessProfile profile = new PageAccessProfile();
        profile.setPageAccessProfileId(id);
        profile.setTenantId(tenantId);
        profile.setStatus(status);
        profile.setPageKeys(new HashSet<>(Arrays.asList(keys)));
        return profile;
    }

    @Test
    void anInactiveOwnProfileFallsThroughToTheDefault() {
        when(this.profileRepository.findById(500L)).thenReturn(Optional.of(this.profile(500L, TENANT_A, Status.Inactive, "jobs")));
        assertThat(this.service.effectivePages(this.tenantUser(TENANT_A, 500L))).containsExactly(PageKey.REPORTS);
    }

    @Test
    void aTenantlessOwnProfileFallsThroughToTheDefault() {
        when(this.profileRepository.findById(501L)).thenReturn(Optional.of(this.profile(501L, null, Status.Active, "jobs")));
        assertThat(this.service.effectivePages(this.tenantUser(TENANT_A, 501L))).containsExactly(PageKey.REPORTS);
    }

    @Test
    void anOwnProfileIdThatIsGoneFallsThroughToTheDefault() {
        when(this.profileRepository.findById(502L)).thenReturn(Optional.empty());
        assertThat(this.service.effectivePages(this.tenantUser(TENANT_A, 502L))).containsExactly(PageKey.REPORTS);
    }

    /** No user is no pages: the null guard is what stops an unresolved person reading as an admin. */
    @Test
    void noUserHoldsNoPages() {
        assertThat(this.service.effectivePages(null)).isEmpty();
    }

    /**
     * PINNED, UNREVIEWED: a TENANT_USER row with no tenant has no default to fall to and cannot match
     * any profile, so it reaches the terminal fallback and holds every page. addUser and updateUser
     * refuse to create such a row and the dev database holds none (0 on 2026-09-24), so it is
     * reachable only by a row written outside the service. Fail-closed would be the empty set.
     */
    @Test
    @Tag("pinned-unreviewed")
    void aTenantUserWithNoTenantHoldsEveryPage() {
        when(this.profileRepository.findById(503L)).thenReturn(Optional.of(this.profile(503L, TENANT_A, Status.Active, "jobs")));

        assertThat(this.service.effectivePages(this.tenantUser(null, 503L))).isEqualTo(PageKey.all());
        verify(this.profileRepository, never()).findByTenantIdAndDefaultProfileTrueAndStatus(any(), any());
    }
}
