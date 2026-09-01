package process.model.service.impl;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import process.model.dto.AuthResponseDto;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.util.JwtUtil;
import process.util.ProcessUtil;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one-time password debt has to reach the console at sign-in.
 *
 * A seeded platform admin and every seeded tenant admin are created with the flag set, but the
 * console used to learn of it only from the profile screen -- so an account could keep using the
 * password it was handed simply by never opening that screen. Sign-in itself is not blocked: the
 * flag is carried, exactly as the profile screen carries it, and the console forces the change.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplMustChangePasswordTest {

    private static final String USERNAME = "platform_admin";
    private static final String PASSWORD = "one-time";

    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AuthServiceImpl(this.appUserRepository, this.tenantRepository,
            this.passwordEncoder, this.jwtUtil);
    }

    /** No tenant, like the seeded platform admin, so the tenant lookup never comes into it. */
    private AppUser platformAdmin(boolean mustChangePassword) {
        AppUser user = new AppUser();
        user.setAppUserId(1L);
        user.setUsername(USERNAME);
        user.setPassword("encoded");
        user.setFullName("Platform Admin");
        user.setUserRole(UserRole.PLATFORM_ADMIN);
        user.setStatus(Status.Active);
        user.setTenantId(null);
        user.setMustChangePassword(mustChangePassword);
        return user;
    }

    private ResponseDto login(AppUser user) throws Exception {
        when(this.appUserRepository.findByUsernameAndStatusNot(USERNAME, Status.Delete))
            .thenReturn(Optional.of(user));
        when(this.passwordEncoder.matches(PASSWORD, user.getPassword())).thenReturn(true);
        when(this.jwtUtil.generateAccessToken(any(AppUser.class))).thenReturn("access");
        when(this.jwtUtil.generateRefreshToken(any(AppUser.class))).thenReturn("refresh");
        when(this.appUserRepository.save(any(AppUser.class))).thenReturn(user);

        LoginRequestDto request = new LoginRequestDto();
        request.setUsername(USERNAME);
        request.setPassword(PASSWORD);
        return this.service.login(request);
    }

    @Test
    void aSeededAdminIsLetInAndTheDebtIsReported() throws Exception {
        ResponseDto response = this.login(this.platformAdmin(true));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        AuthResponseDto auth = (AuthResponseDto) response.getData();
        assertThat(auth.getAccessToken()).isEqualTo("access");
        assertThat(auth.getMustChangePassword())
            .as("the console cannot force the change it is not told about")
            .isTrue();
    }

    @Test
    void anAccountThatOwesNothingIsNotAskedToChangeAnything() throws Exception {
        ResponseDto response = this.login(this.platformAdmin(false));

        AuthResponseDto auth = (AuthResponseDto) response.getData();
        assertThat(auth.getMustChangePassword()).isFalse();
    }

    @Test
    void refreshStillOnlyMintsAnAccessToken() throws Exception {
        // Guards against the flag being read as "you may not refresh": the debt is settled on the
        // console after sign-in, and a session already open must not be cut off midway.
        AppUser user = this.platformAdmin(true);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(USERNAME);
        when(this.jwtUtil.parseClaims("refresh")).thenReturn(claims);
        when(this.jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(this.appUserRepository.findByUsernameAndStatusNot(USERNAME, Status.Delete))
            .thenReturn(Optional.of(user));
        when(this.jwtUtil.generateAccessToken(user)).thenReturn("access");

        ResponseDto response = this.service.refresh("refresh");

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        AuthResponseDto auth = (AuthResponseDto) response.getData();
        assertThat(auth.getAccessToken()).isEqualTo("access");
        assertThat(auth.getRefreshToken()).isNull();
        assertThat(auth.getMustChangePassword())
            .as("refresh must leave the flag alone rather than send false over a standing debt")
            .isNull();
    }

}
