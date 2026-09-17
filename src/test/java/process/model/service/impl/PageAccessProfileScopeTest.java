package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.PageAccessProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.NotificationType;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.PageAccessProfile;
import process.model.repository.AppUserRepository;
import process.model.repository.PageAccessProfileRepository;
import process.model.repository.TenantRepository;
import process.model.service.NotificationCenterService;
import process.security.PageAccessCache;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The profiles themselves: who may touch them, and what a save has to refuse.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class PageAccessProfileScopeTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;
    private static final long ADMIN_A = 9000L;

    @Mock private PageAccessProfileRepository profileRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private NotificationCenterService notificationCenterService;
    @Mock private UserNameResolver userNameResolver;
    @Mock private TenantRepository tenantRepository;

    private PageAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new PageAccessServiceImpl(this.profileRepository, this.appUserRepository,
            this.notificationCenterService, this.userNameResolver, new PageAccessCache(), this.tenantRepository);
        lenient().when(this.profileRepository.save(any())).thenAnswer(inv -> {
            PageAccessProfile p = inv.getArgument(0);
            if (p.getPageAccessProfileId() == null) p.setPageAccessProfileId(1000L);
            return p;
        });
        lenient().when(this.profileRepository.findByTenantIdAndProfileNameIgnoreCaseAndStatus(any(), anyString(), any()))
            .thenReturn(Optional.empty());
        lenient().when(this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(any(), any()))
            .thenReturn(Optional.empty());
        lenient().when(this.appUserRepository.findByPageAccessProfileIdAndStatusNot(any(), any()))
            .thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenantAdmin() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", ADMIN_A, "admin@a.example");
    }

    private PageAccessProfile existing(long id, Long tenantId, String name, boolean isDefault, String... keys) {
        PageAccessProfile p = new PageAccessProfile();
        p.setPageAccessProfileId(id);
        p.setTenantId(tenantId);
        p.setProfileName(name);
        p.setDefaultProfile(isDefault);
        p.setStatus(Status.Active);
        p.setPageKeys(new HashSet<>(Arrays.asList(keys)));
        return p;
    }

    private PageAccessProfileDto dto(String name, String... keys) {
        PageAccessProfileDto d = new PageAccessProfileDto();
        d.setProfileName(name);
        d.setPageKeys(Arrays.asList(keys));
        return d;
    }

    /** The first profile becomes the default, so "I made one and forgot" does not mean "everyone still sees everything". */
    @Test
    void theFirstProfileInAWorkspaceBecomesItsDefault() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.countByTenantIdAndStatus(TENANT_A, Status.Active)).thenReturn(0L);

        ResponseDto response = this.service.addProfile(dto("Operator", "jobs", "queue"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).contains("set as the default");
        ArgumentCaptor<PageAccessProfile> saved = ArgumentCaptor.forClass(PageAccessProfile.class);
        verify(this.profileRepository).save(saved.capture());
        assertThat(saved.getValue().isDefaultProfile()).isTrue();
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(saved.getValue().getPageKeys()).containsExactlyInAnyOrder("jobs", "queue");
    }

    @Test
    void aSecondProfileIsNotTheDefaultUnlessAsked() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.countByTenantIdAndStatus(TENANT_A, Status.Active)).thenReturn(1L);

        this.service.addProfile(dto("Analyst", "reports"));

        ArgumentCaptor<PageAccessProfile> saved = ArgumentCaptor.forClass(PageAccessProfile.class);
        verify(this.profileRepository).save(saved.capture());
        assertThat(saved.getValue().isDefaultProfile()).isFalse();
    }

    @Test
    void aPageThatIsNotInTheCatalogueIsRefusedAtSave() throws Exception {
        this.actAsTenantAdmin();

        ResponseDto response = this.service.addProfile(dto("Odd", "jobs", "admin-users"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("\"admin-users\" is not a page");
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aDuplicateNameInTheSameWorkspaceIsRefused() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.findByTenantIdAndProfileNameIgnoreCaseAndStatus(TENANT_A, "operator", Status.Active))
            .thenReturn(Optional.of(existing(1L, TENANT_A, "Operator", true, "jobs")));

        ResponseDto response = this.service.addProfile(dto("operator", "jobs"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("already exists");
    }

    /** A platform admin has no workspace of its own, so it has to name one -- and a real one. */
    @Test
    void aPlatformAdminMustNameTheWorkspace() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "platform@example.com");

        assertThat(this.service.addProfile(dto("Operator", "jobs")).getMessage()).contains("Say which workspace");
        assertThat(this.service.listProfiles(null).getStatus()).isEqualTo(ProcessUtil.ERROR);
        when(this.tenantRepository.findById(999L)).thenReturn(Optional.empty());
        assertThat(this.service.listProfiles(999L).getMessage()).contains("Tenant not found");
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void aPlatformAdminMakesAProfileInTheWorkspaceItNames() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "platform@example.com");
        when(this.tenantRepository.findById(TENANT_B)).thenReturn(Optional.of(new process.model.pojo.Tenant()));
        when(this.profileRepository.countByTenantIdAndStatus(TENANT_B, Status.Active)).thenReturn(0L);

        PageAccessProfileDto draft = dto("Operator", "jobs");
        draft.setTenantId(TENANT_B);
        ResponseDto response = this.service.addProfile(draft);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<PageAccessProfile> saved = ArgumentCaptor.forClass(PageAccessProfile.class);
        verify(this.profileRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT_B);
    }

    /** A tenant admin's workspace is its own whatever id it sends: it cannot reach into another's. */
    @Test
    void aTenantAdminCannotNameAnotherWorkspace() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.countByTenantIdAndStatus(TENANT_A, Status.Active)).thenReturn(1L);

        PageAccessProfileDto draft = dto("Sneaky", "jobs");
        draft.setTenantId(TENANT_B);
        this.service.addProfile(draft);

        ArgumentCaptor<PageAccessProfile> saved = ArgumentCaptor.forClass(PageAccessProfile.class);
        verify(this.profileRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    void anotherWorkspacesProfileIsNotFoundHere() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.findById(600L)).thenReturn(Optional.of(existing(600L, TENANT_B, "Theirs", false, "jobs")));

        PageAccessProfileDto update = dto("Renamed", "jobs");
        update.setPageAccessProfileId(600L);
        assertThat(this.service.updateProfile(update).getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(this.service.deleteProfile(600L).getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(this.service.setDefaultProfile(600L).getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.profileRepository, never()).save(any());
    }

    /** Dropping people to the default without anyone deciding it is exactly what a delete must not do. */
    @Test
    void aProfileStillHeldBySomebodyCannotBeDeleted() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.findById(1L)).thenReturn(Optional.of(existing(1L, TENANT_A, "Operator", true, "jobs")));
        when(this.appUserRepository.countByPageAccessProfileIdAndStatusNot(1L, Status.Delete)).thenReturn(3L);

        ResponseDto response = this.service.deleteProfile(1L);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("3 people");
        verify(this.profileRepository, never()).save(any());
    }

    @Test
    void changingAProfilesPagesTellsThePeopleOnIt() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.findById(1L)).thenReturn(Optional.of(existing(1L, TENANT_A, "Operator", true, "jobs")));
        AppUser holder = new AppUser();
        holder.setAppUserId(44L); holder.setTenantId(TENANT_A); holder.setUserRole(UserRole.TENANT_USER); holder.setStatus(Status.Active);
        when(this.appUserRepository.findByPageAccessProfileIdAndStatusNot(1L, Status.Delete))
            .thenReturn(Collections.singletonList(holder));

        PageAccessProfileDto update = dto("Operator", "jobs", "reports");
        update.setPageAccessProfileId(1L);
        ResponseDto response = this.service.updateProfile(update);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.notificationCenterService).create(eq(TENANT_A), eq(44L), eq(NotificationType.PAGE_ACCESS_CHANGED),
            any(), eq("Your page access changed"), org.mockito.ArgumentMatchers.contains("Source Jobs, Reports"), eq("/dashboard"));
    }

    @Test
    void aRenameAloneDoesNotNotifyAnybody() throws Exception {
        this.actAsTenantAdmin();
        when(this.profileRepository.findById(1L)).thenReturn(Optional.of(existing(1L, TENANT_A, "Operator", true, "jobs")));

        PageAccessProfileDto update = dto("Operators", "jobs");
        update.setPageAccessProfileId(1L);
        this.service.updateProfile(update);

        verify(this.notificationCenterService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTenantUserAskingForAPageReachesEveryAdminOfTheirWorkspaceOnce() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 44L, "olivia@a.example");
        AppUser me = new AppUser();
        me.setAppUserId(44L); me.setTenantId(TENANT_A); me.setUserRole(UserRole.TENANT_USER); me.setStatus(Status.Active);
        me.setFullName("Olivia Bennett"); me.setUsername("olivia@a.example"); me.setPageAccessProfileId(1L);
        when(this.appUserRepository.findById(44L)).thenReturn(Optional.of(me));
        when(this.profileRepository.findById(1L)).thenReturn(Optional.of(existing(1L, TENANT_A, "Operator", true, "jobs")));
        AppUser admin = new AppUser(); admin.setAppUserId(ADMIN_A); admin.setTenantId(TENANT_A); admin.setUserRole(UserRole.TENANT_ADMIN); admin.setStatus(Status.Active); admin.setFullName("Daniel Carter");
        AppUser peer = new AppUser(); peer.setAppUserId(45L); peer.setTenantId(TENANT_A); peer.setUserRole(UserRole.TENANT_USER); peer.setStatus(Status.Active);
        when(this.appUserRepository.findByTenantIdAndStatusNotOrderByAppUserIdDesc(TENANT_A, Status.Delete))
            .thenReturn(Arrays.asList(admin, peer, me));

        ResponseDto response = this.service.requestAccess("analytics");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).isEqualTo("Asked Daniel Carter to open Analytics Studio for you.");
        verify(this.notificationCenterService).create(eq(TENANT_A), eq(ADMIN_A), eq(NotificationType.PAGE_ACCESS_REQUESTED),
            any(), eq("Page access requested"), org.mockito.ArgumentMatchers.contains("Olivia Bennett"), eq("/users"));
        verify(this.notificationCenterService, never()).create(any(), eq(45L), any(), any(), any(), any(), any());
    }

    @Test
    void askingForAPageYouAlreadyHaveSendsNothing() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 44L, "olivia@a.example");
        AppUser me = new AppUser();
        me.setAppUserId(44L); me.setTenantId(TENANT_A); me.setUserRole(UserRole.TENANT_USER); me.setStatus(Status.Active);
        when(this.appUserRepository.findById(44L)).thenReturn(Optional.of(me));

        ResponseDto response = this.service.requestAccess("jobs");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).contains("already open");
        verify(this.notificationCenterService, never()).create(any(), any(), any(), any(), any(), any(), any());
    }
}
