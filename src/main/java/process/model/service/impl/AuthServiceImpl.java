package process.model.service.impl;

import process.identity.IdentityInProcess;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import process.model.dto.AuthResponseDto;
import process.model.enums.PageKey;
import java.util.stream.Collectors;
import process.model.service.PageAccessService;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.AuthService;
import org.barco.platform.security.LoginAttemptGuard;
import process.security.TokenRevocations;
import org.springframework.beans.factory.annotation.Value;
import process.util.JwtUtil;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@IdentityInProcess
@Service
public class AuthServiceImpl implements AuthService {

    private final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PageAccessService pageAccessService;

    /**
     * The hash of a password nobody knows, matched against when the name is unknown so that
     * path costs what a real one costs: a lookup that answered in a millisecond where a real
     * account took a hundred told a caller which names exist.
     */
    private final String nobodysHash;

    private final LoginAttemptGuard loginAttempts;

    private final TokenRevocations tokenRevocations;

    /**
     * Whether a refresh token is spent by its first use (MIG-14). Off until both consoles keep the
     * rotated token a refresh now returns: the next console merges it into the stored session, the
     * legacy one keeps only the access token and would present the spent one half an hour later --
     * which, with this on, reads as reuse and signs the person out everywhere.
     */
    @Value("${jwt.refresh-token.single-use:false}")
    private boolean singleUseRefreshTokens;

    public AuthServiceImpl(AppUserRepository appUserRepository, TenantRepository tenantRepository,
        PasswordEncoder passwordEncoder, JwtUtil jwtUtil, PageAccessService pageAccessService,
        LoginAttemptGuard loginAttempts, TokenRevocations tokenRevocations) {
        this.loginAttempts = loginAttempts;
        this.tokenRevocations = tokenRevocations;
        this.nobodysHash = passwordEncoder.encode(UUID.randomUUID().toString());
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.pageAccessService = pageAccessService;
    }

    @Override
    public ResponseDto login(LoginRequestDto loginRequestDto) throws Exception {
        if (isNull(loginRequestDto) || isNull(loginRequestDto.getUsername()) || isNull(loginRequestDto.getPassword())) {
            return new ResponseDto(ERROR, "Username and password are required.");
        }
        String username = loginRequestDto.getUsername().trim();
        String invalidCredentialsMessage = "Invalid username or password.";

        // Too many wrong passwords against one name and the name rests, whoever is typing. The count
        // is shared by every instance (MIG-109); when it cannot be read, nobody signs in -- one
        // sentence for every name, asked before any lookup, so the refusal says nothing about who
        // exists. Unlimited guesses for the length of a Redis outage is the alternative.
        long wait;
        try {
            wait = this.loginAttempts.secondsUntilAllowed(username);
        } catch (LoginAttemptGuard.Unavailable ex) {
            return new ResponseDto(ERROR, "Sign-in is unavailable right now. Try again in a few minutes.");
        }
        if (wait > 0) {
            return new ResponseDto(ERROR, String.format("Too many sign-in attempts. Try again in %d minute%s.",
                (wait + 59) / 60, (wait + 59) / 60 == 1 ? "" : "s"));
        }

        // The name is an e-mail address, and e-mail addresses are not case-sensitive: the
        // person who typed a capital in theirs is the same person. One row at most -- a name is
        // unique ignoring case across the platform (MIG-17) -- so login no longer picks one of two.
        Optional<AppUser> userOpt = this.appUserRepository.findLiveByUsernameAcrossTenants(username);

        // The password is checked BEFORE anything is said about the account, and checked even
        // when there is no account -- against a hash that matches nothing -- so a wrong name
        // and a wrong password cost the same time and get the same sentence. The state of an
        // account (inactive, suspended) is told only to somebody who knows its password.
        boolean matches;
        if (userOpt.isPresent()) {
            matches = this.passwordEncoder.matches(loginRequestDto.getPassword(), userOpt.get().getPassword());
        } else {
            this.passwordEncoder.matches(loginRequestDto.getPassword(), this.nobodysHash);
            matches = false;
        }
        if (!matches) {
            this.loginAttempts.failed(username);
            return new ResponseDto(ERROR, invalidCredentialsMessage);
        }
        AppUser user = userOpt.get();
        String suspendedReason = this.checkAccountAndTenantActive(user);
        if (suspendedReason != null) {
            return new ResponseDto(ERROR, suspendedReason);
        }
        this.loginAttempts.succeeded(username);
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
        // MIG-14: a refresh token that was signed out, or minted before its person's standing changed,
        // is as good as expired -- the same sentence, so the console signs in again either way. A
        // single-use token presented twice is reuse: somebody else holds a copy, so every token the
        // person has is ended, the one the rightful holder just received included.
        try {
            if (this.singleUseRefreshTokens && this.tokenRevocations.isDenied(claims)) {
                this.logger.warn("A spent refresh token was presented again for user {}; ending all of their sessions.",
                    this.jwtUtil.appUserIdOf(claims));
                this.tokenRevocations.revokeSessionsOf(this.jwtUtil.appUserIdOf(claims));
                return new ResponseDto(ERROR, "Refresh token is invalid or expired -- please log in again.");
            }
            if (this.tokenRevocations.isRevoked(claims)) {
                return new ResponseDto(ERROR, "Refresh token is invalid or expired -- please log in again.");
            }
        } catch (TokenRevocations.Unavailable ex) {
            return new ResponseDto(ERROR, "Refresh token is invalid or expired -- please log in again.");
        }
        Optional<AppUser> userOpt = this.appUserRepository.findByUsernameAndStatusNot(claims.getSubject(), Status.Delete);
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, "Account no longer active -- please log in again.");
        }
        AppUser user = userOpt.get();
        // Against the database, not only the Redis copy: a bump whose publish failed must not be renewed
        // past by a refresh (MIG-92 made the Redis copy long-lived, for the services that have no database).
        if (TokenRevocations.mintedUnder(claims) < (user.getTokenVersion() == null ? 0 : user.getTokenVersion())) {
            return new ResponseDto(ERROR, "Refresh token is invalid or expired -- please log in again.");
        }

        if (this.checkAccountAndTenantActive(user) != null) {
            return new ResponseDto(ERROR, "Account no longer active -- please log in again.");
        }
        AuthResponseDto response = new AuthResponseDto();
        response.setAccessToken(this.jwtUtil.generateAccessToken(user));
        // Rotated: a new id, the same expiry as the one presented. A console that keeps it can have
        // its old one spent (jwt.refresh-token.single-use); one that ignores it loses nothing.
        response.setRefreshToken(this.jwtUtil.rotateRefreshToken(user, claims.getExpiration()));
        if (this.singleUseRefreshTokens) {
            try {
                this.tokenRevocations.deny(claims);
            } catch (TokenRevocations.Unavailable ex) {
                return new ResponseDto(ERROR, "Refresh token is invalid or expired -- please log in again.");
            }
        }
        return new ResponseDto(SUCCESS, "Token refreshed.", response);
    }

    /**
     * Signs the tokens presented out, on every instance (MIG-14): each is denied until it would have
     * expired. Anything unreadable is ignored and the answer is the same -- sign-out tells nobody
     * whether what they held was ever good.
     */
    @Override
    public ResponseDto logout(String accessToken, String refreshToken) {
        for (String token : new String[] {accessToken, refreshToken}) {
            if (isNull(token) || token.trim().isEmpty()) {
                continue;
            }
            Claims claims;
            try {
                claims = this.jwtUtil.parseClaims(token.trim());
            } catch (JwtException | IllegalArgumentException ex) {
                continue;
            }
            try {
                this.tokenRevocations.deny(claims);
            } catch (TokenRevocations.Unavailable ex) {
                return new ResponseDto(ERROR, "Sign-out could not be recorded. Try again in a few minutes.");
            }
        }
        return new ResponseDto(SUCCESS, "Signed out.");
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
        // Without these the header showed initials until the profile screen happened to be
        // opened, which is the only other thing that fetches them.
        response.setAvatarBucket(user.getAvatarBucket());
        response.setAvatarKey(user.getAvatarKey());
        // The seeded platform admin and every tenant-seeded admin are created owing a password
        // change. Without it here the console only learned of the debt from the profile screen,
        // so a one-time password could be used indefinitely by never opening that screen. Sign-in
        // still succeeds -- the change is forced by the console, as it is for every other account
        // carrying the flag.
        response.setMustChangePassword(user.isMustChangePassword());
        // The menu is built from this on the first paint. An admin gets every key; a tenant
        // user gets their profile's, and the profile's name so the header can say which.
        response.setPageKeys(PageKey.all().stream()
            .filter(this.pageAccessService.effectivePages(user)::contains)
            .map(PageKey::getKey).collect(Collectors.toList()));
        response.setPageAccessProfileName(this.pageAccessService.profileNameFor(user.getPageAccessProfileId()));
        return response;
    }

}
