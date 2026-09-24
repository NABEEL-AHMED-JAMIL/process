package process.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.ScopedAppUserReads;
import process.model.repository.TenantRepository;
import process.security.PageGate;
import process.security.TokenRevocations;
import process.util.JwtUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link IdentityPort} answered from the monolith's own Identity tables (MIG-93) -- the in-process side
 * of the carve, as AiPort's was before ai-service. When Identity moves out, an HTTP implementation
 * replaces this one and nothing that calls the port changes.
 *
 * The page gate comes through an ObjectProvider: the gate reads page access, page access names people
 * through the port, and a constructor cycle there would stop process at startup.
 *
 * @author Nabeel Ahmed
 */
@IdentityInProcess
@Component
public class LocalIdentity implements IdentityPort {

    private static final Logger logger = LoggerFactory.getLogger(LocalIdentity.class);

    private final JwtUtil jwtUtil;
    private final TokenRevocations revocations;
    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final ObjectProvider<PageGate> gate;

    public LocalIdentity(JwtUtil jwtUtil, TokenRevocations revocations, AppUserRepository users, TenantRepository tenants,
        ObjectProvider<PageGate> gate) {
        this.jwtUtil = jwtUtil;
        this.revocations = revocations;
        this.users = users;
        this.tenants = tenants;
        this.gate = gate;
    }

    @Override
    public Optional<CallerIdentity> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.trim().isEmpty()) {
            return Optional.empty();
        }
        Claims claims;
        try {
            claims = this.jwtUtil.parseClaims(bearerToken.trim());
        } catch (JwtException | IllegalArgumentException ex) {
            logger.debug("Token not accepted: {}", ex.getMessage());
            return Optional.empty();
        }
        if (this.jwtUtil.isRefreshToken(claims)) {
            return Optional.empty();
        }
        try {
            if (this.revocations.isRevoked(claims)) {
                return Optional.empty();
            }
        } catch (TokenRevocations.Unavailable ex) {
            logger.warn("A token was refused: revocations cannot be checked");
            return Optional.empty();
        }
        try {
            return Optional.of(new CallerIdentity(this.jwtUtil.appUserIdOf(claims), this.jwtUtil.tenantIdOf(claims),
                this.jwtUtil.userRoleOf(claims), claims.getSubject(), this.jwtUtil.owesPasswordChange(claims), claims.getId(),
                TokenRevocations.mintedUnder(claims)));
        } catch (JwtException | IllegalArgumentException malformed) {
            // A claim of the wrong type -- a tenant id that is not a number -- is a token nobody we know
            // minted: it authenticates nobody, and so can never widen a scope (MIG-93).
            logger.debug("Token with a malformed claim: {}", malformed.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Person> person(Long appUserId) {
        return appUserId == null ? Optional.empty() : this.users.findById(appUserId).map(LocalIdentity::toPerson);
    }

    @Override
    public Map<Long, Person> people(Collection<Long> appUserIds) {
        Set<Long> wanted = appUserIds == null ? new HashSet<>()
            : appUserIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Person> found = new HashMap<>();
        if (!wanted.isEmpty()) {
            // By id, across tenants: a name is put to an id, never used to decide access (MIG-13).
            for (AppUser user : this.users.findAllByIdAcrossTenants(wanted)) {
                found.put(user.getAppUserId(), toPerson(user));
            }
        }
        return found;
    }

    @Override
    public Optional<Workspace> workspace(Long tenantId) {
        return tenantId == null ? Optional.empty() : this.tenants.findById(tenantId).map(LocalIdentity::toWorkspace);
    }

    @Override
    public Optional<Workspace> workspaceByCode(String code) {
        return code == null ? Optional.empty() : this.tenants.findByTenantCode(code).map(LocalIdentity::toWorkspace);
    }

    @Override
    public List<Workspace> workspaces(Collection<Long> tenantIds) {
        List<Workspace> found = new ArrayList<>();
        if (tenantIds != null && !tenantIds.isEmpty()) {
            this.tenants.findAllById(tenantIds).forEach(tenant -> found.add(toWorkspace(tenant)));
        }
        return found;
    }

    @Override
    public List<Workspace> liveWorkspaces() {
        return this.tenants.findByStatusNotOrderByTenantIdDesc(TenantStatus.Delete).stream()
            .map(LocalIdentity::toWorkspace).collect(Collectors.toList());
    }

    @Override
    public List<Person> members(TenantScope scope) {
        return ScopedAppUserReads.findLive(this.users, scope).stream().map(LocalIdentity::toPerson).collect(Collectors.toList());
    }

    @Override
    public long seats(Long tenantId) {
        return tenantId == null ? 0 : this.users.countByTenantIdAndStatusNot(tenantId, Status.Delete);
    }

    @Override
    public PageDecision pageDecision(String userRole, Long appUserId, String servletPath) {
        PageGate.Decision decision = this.gate.getObject().decide(userRole, appUserId, servletPath);
        return new PageDecision(decision.isAllowed(), decision.getMessage());
    }

    static Person toPerson(AppUser user) {
        return new Person(user.getAppUserId(), user.getTenantId(), user.getUsername(), user.getFullName(),
            user.getUserRole() == null ? null : user.getUserRole().name(), user.getStatus() == null ? null : user.getStatus().name(),
            user.getAvatarBucket(), user.getAvatarKey());
    }

    static Workspace toWorkspace(Tenant tenant) {
        return new Workspace(tenant.getTenantId(), tenant.getTenantName(), tenant.getTenantCode(),
            tenant.getStatus() == null ? null : tenant.getStatus().name());
    }
}
