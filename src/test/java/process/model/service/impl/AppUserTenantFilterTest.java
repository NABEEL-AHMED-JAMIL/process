package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.AppUserDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.PageAccessService;
import process.notifications.TestNotifications;
import org.barco.platform.tenancy.TenantScope;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TokenRevocations;
import process.storage.TrustedStorageOperations;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-13, the service half: the users list turns the tenant filter on before it reads, and a person
 * who stops being a tenant user loses their page exceptions before their row is saved -- the
 * exceptions carry the person's tenant now, and a promotion to platform admin clears it.
 */
@ExtendWith(MockitoExtension.class)
class AppUserTenantFilterTest {

    private static final long TENANT_A = 1001L;

    @Mock private AppUserRepository appUserRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PageAccessService pageAccessService;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private EntityManager entityManager;

    private AppUserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AppUserServiceImpl(this.appUserRepository, this.tenantRepository, this.passwordEncoder,
            TestNotifications.recording(null, mock(TestNotifications.NoticeSink.class), mock(TestNotifications.MailSink.class)),
            mock(UserNameResolver.class), mock(TrustedStorageOperations.class), this.pageAccessService, this.tenantFilterHelper, mock(TokenRevocations.class));
        ReflectionTestUtils.setField(this.service, "entityManager", this.entityManager);
        lenient().when(this.pageAccessService.accessSummaryFor(any())).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AppUser tenantUser(long id) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setTenantId(TENANT_A);
        user.setUsername("u@a.example");
        user.setFullName("U");
        user.setUserRole(UserRole.TENANT_USER);
        user.setStatus(Status.Active);
        return user;
    }

    @Test
    void theUsersListTurnsTheFilterOnBeforeItReads() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 9L, "admin@a.example");
        when(this.appUserRepository.findByTenantIdAndStatusNotOrderByAppUserIdDesc(TENANT_A, Status.Delete))
            .thenReturn(Collections.singletonList(this.tenantUser(44L)));

        assertThat(this.service.listUsers().getStatus()).isEqualTo(ProcessUtil.SUCCESS);

        InOrder order = inOrder(this.tenantFilterHelper, this.appUserRepository);
        order.verify(this.tenantFilterHelper).enableIfNeeded(this.entityManager);
        order.verify(this.appUserRepository).findByTenantIdAndStatusNotOrderByAppUserIdDesc(TENANT_A, Status.Delete);
    }

    /**
     * MIG-93: a tenant admin whose token carries no tenant reads nobody. Before TenantScope it reached
     * findByTenantIdAndStatusNot(null, ...), which Spring Data derives as "tenant_id IS NULL" -- every
     * platform administrator's row.
     */
    @Test
    void aTenantAdminWithNoTenantListsNobodyNotThePlatformAdmins() throws Exception {
        TenantContext.set(null, "TENANT_ADMIN", 9L, "admin@nowhere.example");

        assertThat(this.service.listUsers().getStatus()).isEqualTo(ProcessUtil.SUCCESS);

        verify(this.appUserRepository).findByTenantIdAndStatusNotOrderByAppUserIdDesc(TenantScope.NO_TENANT_MATCHES, Status.Delete);
        verify(this.appUserRepository, never()).findByTenantIdAndStatusNotOrderByAppUserIdDesc(null, Status.Delete);
        verify(this.appUserRepository, never()).findAllLiveAcrossTenants();
    }

    @Test
    void aPlatformAdminListsEveryTenantThroughTheNamedGrant() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");

        this.service.listUsers();

        verify(this.appUserRepository).findAllLiveAcrossTenants();
        verify(this.appUserRepository, never()).findAll();
    }

    @Test
    void aPromotionToPlatformAdminDropsTheExceptionsBeforeTheSave() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        AppUser user = this.tenantUser(44L);
        when(this.appUserRepository.findById(44L)).thenReturn(Optional.of(user));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(44L);
        dto.setFullName("U");
        dto.setUserRole(UserRole.PLATFORM_ADMIN);
        assertThat(this.service.updateUser(dto).getStatus()).isEqualTo(ProcessUtil.SUCCESS);

        InOrder order = inOrder(this.pageAccessService, this.appUserRepository);
        order.verify(this.pageAccessService).dropExceptions(44L);
        order.verify(this.appUserRepository).save(user);
        assertThat(user.getTenantId()).isNull();
    }

    @Test
    void aPromotionToTenantAdminDropsThemToo() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        when(this.appUserRepository.findById(44L)).thenReturn(Optional.of(this.tenantUser(44L)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(44L);
        dto.setFullName("U");
        dto.setUserRole(UserRole.TENANT_ADMIN);
        this.service.updateUser(dto);

        verify(this.pageAccessService).dropExceptions(44L);
    }

    /** Moving a tenant user to another tenant keeps them: the database carries them across (ON UPDATE CASCADE). */
    @Test
    void aTenantUserWhoStaysATenantUserKeepsThemEvenAcrossTenants() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        when(this.appUserRepository.findById(44L)).thenReturn(Optional.of(this.tenantUser(44L)));
        when(this.tenantRepository.findById(2002L)).thenReturn(Optional.of(new Tenant()));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(44L);
        dto.setFullName("U");
        dto.setTenantId(2002L);
        dto.setUserRole(UserRole.TENANT_USER);
        this.service.updateUser(dto);

        verify(this.pageAccessService, never()).dropExceptions(any());
    }
}
