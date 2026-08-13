package process.model.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import process.model.dto.AuthResponseDto;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.AuthService;
import process.util.JwtUtil;
import java.sql.Timestamp;
import java.util.Optional;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

@Service
public class AuthServiceImpl implements AuthService {

    private final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthServiceImpl(AppUserRepository appUserRepository, TenantRepository tenantRepository,
        PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public ResponseDto login(LoginRequestDto loginRequestDto) throws Exception {
        if (isNull(loginRequestDto) || isNull(loginRequestDto.getUsername()) || isNull(loginRequestDto.getPassword())) {
            return new ResponseDto(ERROR, "Username and password are required.");
        }
        Optional<AppUser> userOpt = this.appUserRepository.findByUsernameAndStatusNot(
            loginRequestDto.getUsername().trim(), Status.Delete);

        String invalidCredentialsMessage = "Invalid username or password.";
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, invalidCredentialsMessage);
        }
        AppUser user = userOpt.get();
        String suspendedReason = this.checkAccountAndTenantActive(user);
        if (suspendedReason != null) {
            return new ResponseDto(ERROR, suspendedReason);
        }
        if (!this.passwordEncoder.matches(loginRequestDto.getPassword(), user.getPassword())) {
            return new ResponseDto(ERROR, invalidCredentialsMessage);
        }
        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, "Login successful.", this.buildAuthResponse(user));
    }

    @Override
    public ResponseDto refresh(String refreshToken) throws Exception {
        if (isNull(refreshToken) || refreshToken.trim().isEmpty()) {
            return new ResponseDto(ERROR, "Refresh token missing.");
        }
        Claims claims;
        try {
            claims = this.jwtUtil.parseClaims(refreshToken);
        } catch (JwtException | IllegalArgumentException ex) {
            this.logger.debug("Rejected refresh token: {}", ex.getMessage());
            return new ResponseDto(ERROR, "Refresh token is invalid or expired -- please log in again.");
        }
        if (!this.jwtUtil.isRefreshToken(claims)) {
            return new ResponseDto(ERROR, "Not a refresh token.");
        }
        Optional<AppUser> userOpt = this.appUserRepository.findByUsernameAndStatusNot(claims.getSubject(), Status.Delete);
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, "Account no longer active -- please log in again.");
        }
        AppUser user = userOpt.get();

        if (this.checkAccountAndTenantActive(user) != null) {
            return new ResponseDto(ERROR, "Account no longer active -- please log in again.");
        }
        AuthResponseDto response = new AuthResponseDto();
        response.setAccessToken(this.jwtUtil.generateAccessToken(user));
        return new ResponseDto(SUCCESS, "Token refreshed.", response);
    }

    private String checkAccountAndTenantActive(AppUser user) {
        if (user.getStatus() != Status.Active) {
            return "This account is inactive. Contact your administrator.";
        }
        if (!isNull(user.getTenantId())) {
            Optional<Tenant> tenantOpt = this.tenantRepository.findById(user.getTenantId());
            if (!tenantOpt.isPresent() || tenantOpt.get().getStatus() != TenantStatus.Active) {
                return "Your organization's access is currently suspended. Contact your administrator.";
            }
        }
        return null;
    }

    private AuthResponseDto buildAuthResponse(AppUser user) {
        AuthResponseDto response = new AuthResponseDto();
        response.setAccessToken(this.jwtUtil.generateAccessToken(user));
        response.setRefreshToken(this.jwtUtil.generateRefreshToken(user));
        response.setUsername(user.getUsername());
        response.setFullName(user.getFullName());
        response.setUserRole(user.getUserRole().name());
        response.setTenantId(user.getTenantId());
        response.setAppUserId(user.getAppUserId());
        return response;
    }

}
