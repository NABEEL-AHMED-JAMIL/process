package process.identity;

import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.error.PlatformException;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.JwksKeys;
import org.barco.platform.security.JwtVerifier;
import org.barco.platform.security.RevocationCheck;
import org.barco.platform.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import process.model.enums.PageKey;
import process.model.enums.UserRole;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * {@link IdentityPort} over HTTP, once Identity is its own service (identity.mode=remote, MIG-107).
 *
 * A token is verified here, not by asking Identity: its signature against the keys Identity publishes
 * (JWKS by kid, RS256) or, while the owner has not flipped it off, the shared HS256 secret; then the
 * shared revocations in Redis -- the same keys Identity writes on sign-out and on every change of a
 * person's standing. Nothing about authenticating a request waits on Identity being up.
 *
 * The directory (people, workspaces, seats, members) is one POST to /internal/identity/* with the
 * service token; a failure is {@link IdentityPort.Unavailable}. The page gate asks Identity only for a
 * tenant user on a gated path -- the two checks PageGate makes first are made here, with no call -- and
 * keeps each answer for {@link #PAGE_TTL_MILLIS}, as PageAccessCache did in process. An answer that
 * cannot be had refuses the request: page access fails closed.
 */
@Component
@ConditionalOnProperty(name = "identity.mode", havingValue = "remote")
public class HttpIdentity implements IdentityPort {

    private static final Logger logger = LoggerFactory.getLogger(HttpIdentity.class);

    /** How long a page decision is trusted: PageAccessCache's TTL, unchanged. */
    public static final long PAGE_TTL_MILLIS = 15_000L;

    /** The sentence a tenant user reads when their page access cannot be checked. */
    static final String PAGE_GATE_UNAVAILABLE = "Page access cannot be checked right now. Try again in a moment.";

    private static final PageDecision ALLOWED = new PageDecision(true, null);
    private static final int MAX_CACHED_DECISIONS = 10_000;

    private final RestTemplate http;
    private final String base;
    private final String serviceToken;
    private final JwtVerifier verifier;
    private final LongSupplier clock;
    private final Map<String, CachedDecision> decisions = new ConcurrentHashMap<>();

    @Autowired
    public HttpIdentity(@Value("${identity.url:http://identity:9160}") String identityUrl,
        @Value("${internal.service-token:}") String serviceToken,
        @Value("${jwt.secret.key:}") String hs256Secret, @Value("${jwt.accept-hs256:true}") boolean acceptHs256,
        @Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this(timed(), identityUrl, serviceToken, verifier(identityUrl, hs256Secret, acceptHs256, redis), System::currentTimeMillis);
    }

    HttpIdentity(RestTemplate http, String identityUrl, String serviceToken, JwtVerifier verifier, LongSupplier clock) {
        this.http = http;
        this.base = identityUrl.replaceAll("/+$", "") + "/api/v1/internal/identity";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.verifier = verifier;
        this.clock = clock;
    }

    private static RestTemplate timed() {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(1000);
        timeouts.setReadTimeout(3000);
        return new RestTemplate(timeouts);
    }

    /** Identity's JWKS by kid; HS256 only while it is still accepted; the shared revocations always. */
    static JwtVerifier verifier(String identityUrl, String hs256Secret, boolean acceptHs256, RedisTemplate<String, String> redis) {
        JwtVerifier.Builder builder = JwtVerifier.builder()
            .rs256(new JwksKeys(URI.create(identityUrl.replaceAll("/+$", "") + "/api/v1/internal/jwks")))
            .revocations(new RevocationCheck(redis));
        if (acceptHs256 && hs256Secret != null && !hs256Secret.trim().isEmpty()) {
            builder.hs256(hs256Secret.trim(), true);
        }
        return builder.build();
    }

    @Override
    public Optional<CallerIdentity> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(this.verifier.verify(bearerToken.trim()));
        } catch (PlatformException refused) {
            logger.debug("Token not accepted: {}", refused.getMessage());
            return Optional.empty();
        } catch (RuntimeException malformed) {
            // A claim of the wrong type is a token nobody we know minted: it authenticates nobody (MIG-93).
            logger.debug("Token with a malformed claim: {}", malformed.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Person> person(Long appUserId) {
        return appUserId == null ? Optional.empty() : Optional.ofNullable(this.people(Collections.singletonList(appUserId)).get(appUserId));
    }

    @Override
    public Map<Long, Person> people(Collection<Long> appUserIds) {
        Set<Long> wanted = appUserIds == null ? new TreeSet<>()
            : appUserIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        Map<Long, Person> found = new HashMap<>();
        if (wanted.isEmpty()) {
            return found;
        }
        for (List<Long> batch : batches(wanted)) {
            for (Map<String, Object> row : this.rows("people", Collections.singletonMap("ids", batch))) {
                Person person = toPerson(row);
                found.put(person.getAppUserId(), person);
            }
        }
        return found;
    }

    @Override
    public Optional<Workspace> workspace(Long tenantId) {
        return tenantId == null ? Optional.empty() : this.workspaces(Collections.singletonList(tenantId)).stream().findFirst();
    }

    @Override
    public Optional<Workspace> workspaceByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            Map<?, ?> row = this.http.postForObject(this.base + "/workspaceByCode",
                this.request(Collections.singletonMap("code", code)), Map.class);
            return row == null ? Optional.empty() : Optional.of(toWorkspace(row));
        } catch (HttpClientErrorException.NotFound none) {
            return Optional.empty();
        } catch (RestClientException ex) {
            throw unavailable("workspaceByCode", ex);
        }
    }

    @Override
    public List<Workspace> workspaces(Collection<Long> tenantIds) {
        List<Workspace> found = new ArrayList<>();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return found;
        }
        Set<Long> wanted = tenantIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        for (List<Long> batch : batches(wanted)) {
            for (Map<String, Object> row : this.rows("workspaces", Collections.singletonMap("ids", batch))) {
                found.add(toWorkspace(row));
            }
        }
        return found;
    }

    @Override
    public List<Workspace> liveWorkspaces() {
        return this.rows("liveWorkspaces", Collections.emptyMap()).stream().map(HttpIdentity::toWorkspace).collect(Collectors.toList());
    }

    @Override
    public List<Person> members(TenantScope scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (scope.isAllTenants()) {
            body.put("allTenants", true);
        } else {
            long tenantId = ((TenantScope.Scoped) scope).tenantId();
            if (tenantId == TenantScope.NO_TENANT_MATCHES) {
                return new ArrayList<>();
            }
            body.put("tenantId", tenantId);
        }
        return this.rows("members", body).stream().map(HttpIdentity::toPerson).collect(Collectors.toList());
    }

    @Override
    public long seats(Long tenantId) {
        if (tenantId == null) {
            return 0;
        }
        try {
            Map<?, ?> answer = this.http.postForObject(this.base + "/seats", this.request(Collections.singletonMap("tenantId", tenantId)), Map.class);
            Object seats = answer == null ? null : answer.get("seats");
            if (!(seats instanceof Number)) {
                throw new IdentityPort.Unavailable("Identity answered seats without a number", null);
            }
            return ((Number) seats).longValue();
        } catch (RestClientException ex) {
            throw unavailable("seats", ex);
        }
    }

    @Override
    public PageDecision pageDecision(String userRole, Long appUserId, String servletPath) {
        // PageGate's own first two checks, made here: only a tenant user on a gated path is ever asked about.
        if (!UserRole.TENANT_USER.name().equals(userRole)) {
            return ALLOWED;
        }
        Set<PageKey> gating = PageKey.pagesGating(servletPath);
        if (gating.isEmpty()) {
            return ALLOWED;
        }
        // The decision depends only on the person and the pages the path needs, so that is the key.
        String key = appUserId + "|" + gating.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        long now = this.clock.getAsLong();
        CachedDecision cached = this.decisions.get(key);
        if (cached != null && cached.expiresAt > now) {
            return cached.decision;
        }
        PageDecision decision;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userRole", userRole);
            body.put("appUserId", appUserId);
            body.put("path", servletPath);
            Map<?, ?> answer = this.http.postForObject(this.base + "/pageDecision", this.request(body), Map.class);
            if (answer == null || !(answer.get("allowed") instanceof Boolean)) {
                throw new RestClientException("no decision in the answer");
            }
            Object message = answer.get("message");
            decision = new PageDecision((Boolean) answer.get("allowed"), message == null ? null : message.toString());
        } catch (RestClientException ex) {
            logger.warn("Refused {} for user {}: page access cannot be checked: {}", servletPath, appUserId, ex.getMessage());
            return new PageDecision(false, PAGE_GATE_UNAVAILABLE);
        }
        if (this.decisions.size() >= MAX_CACHED_DECISIONS) {
            this.decisions.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
            if (this.decisions.size() >= MAX_CACHED_DECISIONS) {
                this.decisions.clear();
            }
        }
        this.decisions.put(key, new CachedDecision(decision, now + PAGE_TTL_MILLIS));
        return decision;
    }

    @SuppressWarnings("unchecked")
    /** Identity's /people and /workspaces answer at most this many ids at once, and refuse more. */
    static final int MAX_IDS_PER_CALL = 500;

    private static List<List<Long>> batches(Set<Long> ids) {
        List<Long> all = new ArrayList<>(ids);
        List<List<Long>> batches = new ArrayList<>();
        for (int from = 0; from < all.size(); from += MAX_IDS_PER_CALL) {
            batches.add(all.subList(from, Math.min(all.size(), from + MAX_IDS_PER_CALL)));
        }
        return batches;
    }

    private List<Map<String, Object>> rows(String operation, Object body) {
        try {
            Object[] answer = this.http.postForObject(this.base + "/" + operation, this.request(body), Object[].class);
            List<Map<String, Object>> rows = new ArrayList<>();
            if (answer != null) {
                for (Object row : answer) {
                    if (row instanceof Map) {
                        rows.add((Map<String, Object>) row);
                    }
                }
            }
            return rows;
        } catch (RestClientException ex) {
            throw unavailable(operation, ex);
        }
    }

    private HttpEntity<Object> request(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", this.serviceToken);
        if (CorrelationId.current() != null) {
            headers.set(CorrelationId.HEADER, CorrelationId.current());
        }
        return new HttpEntity<>(body, headers);
    }

    private static IdentityPort.Unavailable unavailable(String operation, RestClientException ex) {
        logger.warn("Identity could not answer {}: {}", operation, ex.getMessage());
        return new IdentityPort.Unavailable("Identity could not answer " + operation, ex);
    }

    static Person toPerson(Map<?, ?> row) {
        return new Person(longOf(row.get("appUserId")), longOf(row.get("tenantId")), textOf(row.get("username")),
            textOf(row.get("fullName")), textOf(row.get("userRole")), textOf(row.get("status")),
            textOf(row.get("avatarBucket")), textOf(row.get("avatarKey")));
    }

    static Workspace toWorkspace(Map<?, ?> row) {
        return new Workspace(longOf(row.get("tenantId")), textOf(row.get("name")), textOf(row.get("code")), textOf(row.get("status")));
    }

    private static Long longOf(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static String textOf(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class CachedDecision {
        final PageDecision decision;
        final long expiresAt;

        CachedDecision(PageDecision decision, long expiresAt) {
            this.decision = decision;
            this.expiresAt = expiresAt;
        }
    }
}
