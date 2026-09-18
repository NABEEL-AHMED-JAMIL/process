package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.ai.AiEndpointPolicy;
import process.ai.AiProviderGateway;
import process.model.dto.AiModelConnectionDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.Tenant;
import process.model.repository.AiModelConnectionRepository;
import process.model.repository.AiPromptRepository;
import process.model.repository.AiPromptRunRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * Model connections: where prompts run. Scoped like Kafka connection profiles -- a tenant
 * sees and edits its own, a platform admin every workspace's -- with one default per
 * workspace that a prompt naming no connection falls back to. The key is encrypted at rest,
 * returned to nobody, and kept when an edit sends it blank.
 */
@Service
public class AiModelConnectionServiceImpl {

    private final Logger logger = LoggerFactory.getLogger(AiModelConnectionServiceImpl.class);
    private static final Set<String> KEYLESS = new HashSet<>(Collections.singletonList("Ollama"));
    private static final Set<String> BUILT_IN_ENDPOINT = new HashSet<>(Arrays.asList("OpenAI", "Anthropic", "Ollama"));

    private final AiModelConnectionRepository connections;
    private final AiPromptRepository prompts;
    private final AiPromptRunRepository runs;
    private final TenantRepository tenants;
    private final EncryptionUtil encryptionUtil;
    private final AiEndpointPolicy endpointPolicy;
    private final AiProviderGateway gateway;
    private final UserNameResolver userNameResolver;
    private final Gson gson = new Gson();

    public AiModelConnectionServiceImpl(AiModelConnectionRepository connections, AiPromptRepository prompts,
        AiPromptRunRepository runs, TenantRepository tenants, EncryptionUtil encryptionUtil,
        AiEndpointPolicy endpointPolicy, AiProviderGateway gateway, UserNameResolver userNameResolver) {
        this.connections = connections; this.prompts = prompts; this.runs = runs; this.tenants = tenants;
        this.encryptionUtil = encryptionUtil; this.endpointPolicy = endpointPolicy; this.gateway = gateway;
        this.userNameResolver = userNameResolver;
    }

    public ResponseDto list() {
        List<AiModelConnection> visible = TenantContext.isPlatformAdmin()
            ? this.connections.findVisibleToPlatformAdmin(Status.Delete)
            : this.connections.findVisibleToTenant(TenantContext.getTenantId(), Status.Delete);
        this.userNameResolver.attachNames(visible);
        Map<Long, String> tenantNames = new HashMap<>();
        if (TenantContext.isPlatformAdmin()) {
            for (Tenant t : this.tenants.findAll()) tenantNames.put(t.getTenantId(), t.getTenantName());
        }
        List<AiModelConnectionDto> rows = visible.stream().map(c -> this.toDto(c, tenantNames, true)).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, String.format("%d connection(s).", rows.size()), rows);
    }

    /** The default connection for a workspace, if it has one; null tenant means the platform's. */
    public Optional<AiModelConnection> defaultFor(Long tenantId) {
        return this.connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(tenantId, Status.Active);
    }

    /** A connection the caller may use, or empty: not there, deleted, or another workspace's. */
    public Optional<AiModelConnection> scopedFind(Long connectionId) {
        if (isNull(connectionId)) return Optional.empty();
        Optional<AiModelConnection> found = this.connections.findById(connectionId)
            .filter(c -> c.getStatus() != Status.Delete);
        if (!found.isPresent() || TenantContext.isPlatformAdmin()) return found;
        Long owner = found.get().getTenantId();
        return owner != null && owner.equals(TenantContext.getTenantId()) ? found : Optional.empty();
    }

    @Transactional
    public ResponseDto save(AiModelConnectionDto dto) {
        ResponseDto refused = this.validate(dto);
        if (refused != null) return refused;
        AiModelConnection c;
        if (dto.getConnectionId() != null) {
            Optional<AiModelConnection> existing = this.scopedFind(dto.getConnectionId());
            if (!existing.isPresent()) return new ResponseDto(ERROR, String.format("Connection not found with %d.", dto.getConnectionId()));
            c = existing.get();
        } else {
            c = new AiModelConnection();
            c.setTenantId(TenantContext.isPlatformAdmin() ? dto.getTenantId() : TenantContext.getTenantId());
            c.setIsDefault(false);
            c.setDateCreated(new Timestamp(System.currentTimeMillis()));
        }
        c.setName(dto.getName().trim());
        c.setProvider(dto.getProvider().trim());
        c.setApiEndpoint(isNull(dto.getApiEndpoint()) || dto.getApiEndpoint().trim().isEmpty() ? null : dto.getApiEndpoint().trim());
        c.setDefaultModel(dto.getDefaultModel().trim());
        c.setMaxConcurrency(dto.getMaxConcurrency() == null || dto.getMaxConcurrency() < 1 ? 4 : Math.min(dto.getMaxConcurrency(), 64));
        c.setDailyTokenBudget(dto.getDailyTokenBudget() == null || dto.getDailyTokenBudget() < 1 ? null : dto.getDailyTokenBudget());
        if (dto.getStatus() != null && !dto.getStatus().isEmpty() && !"Delete".equals(dto.getStatus())) c.setStatus(Status.valueOf(dto.getStatus()));
        // A blank key on an edit keeps the stored one; a keyless provider stores none.
        if (KEYLESS.contains(c.getProvider())) c.setApiKey(null);
        else if (!isNull(dto.getApiKey()) && !dto.getApiKey().trim().isEmpty()) c.setApiKey(this.encryptionUtil.encrypt(dto.getApiKey().trim()));
        if (!KEYLESS.contains(c.getProvider()) && isNull(c.getApiKey())) return new ResponseDto(ERROR, "This provider needs an API key.");
        c = this.connections.save(c);
        // The first connection a workspace makes is its default; there is nothing else to fall back to.
        if (c.getTenantId() != null && !this.defaultFor(c.getTenantId()).isPresent() && c.getStatus() == Status.Active) {
            c.setIsDefault(true);
            c = this.connections.save(c);
        }
        return new ResponseDto(SUCCESS, String.format("\"%s\" saved.", c.getName()), this.toDto(c, Collections.emptyMap(), false));
    }

    @Transactional
    public ResponseDto setDefault(Long connectionId) {
        Optional<AiModelConnection> found = this.scopedFind(connectionId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Connection not found with %d.", connectionId));
        AiModelConnection c = found.get();
        if (c.getStatus() != Status.Active) return new ResponseDto(ERROR, "An inactive connection cannot be the default.");
        if (c.getTenantId() == null) return new ResponseDto(ERROR, "A platform connection has no workspace to be the default of.");
        this.connections.clearDefaultForTenantExcept(c.getTenantId(), c.getConnectionId());
        c.setIsDefault(true);
        this.connections.save(c);
        return new ResponseDto(SUCCESS, String.format("\"%s\" is now the default model connection.", c.getName()));
    }

    @Transactional
    public ResponseDto delete(Long connectionId) {
        Optional<AiModelConnection> found = this.scopedFind(connectionId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Connection not found with %d.", connectionId));
        AiModelConnection c = found.get();
        long using = this.prompts.countByConnectionIdAndStatusNot(connectionId, Status.Delete);
        if (using > 0) {
            return new ResponseDto(ERROR, String.format("%d prompt(s) still run on this connection. Point them elsewhere first.", using));
        }
        // The default carries every prompt that names no connection; taking it away strands them.
        if (Boolean.TRUE.equals(c.getIsDefault()) && c.getTenantId() != null) {
            long onDefault = this.prompts.countByTenantIdAndConnectionIdIsNullAndStatusNot(c.getTenantId(), Status.Delete);
            if (onDefault > 0) {
                return new ResponseDto(ERROR, String.format("%d prompt(s) run on the workspace default, which this is. Make another connection the default first.", onDefault));
            }
        }
        c.setStatus(Status.Delete);
        c.setIsDefault(false);
        this.connections.save(c);
        return new ResponseDto(SUCCESS, "Connection deleted.");
    }

    /** Asks the provider for its models; records the outcome on the row either way. */
    @Transactional
    public ResponseDto test(Long connectionId) {
        Optional<AiModelConnection> found = this.scopedFind(connectionId);
        if (!found.isPresent()) return new ResponseDto(ERROR, String.format("Connection not found with %d.", connectionId));
        AiModelConnection c = found.get();
        c.setLastTestedAt(new Timestamp(System.currentTimeMillis()));
        try {
            List<String> models = this.gateway.listModels(c.getProvider(), this.keyOf(c), c.getApiEndpoint());
            c.setLastTestOk(true);
            c.setLastTestMessage(models.isEmpty()
                ? "Reached the provider; it does not list models, so the ones typed here stand."
                : String.format("Reached the provider; %d model(s) listed.", models.size()));
            if (!models.isEmpty()) c.setModelsListed(this.gson.toJson(models));
            this.connections.save(c);
            return new ResponseDto(SUCCESS, c.getLastTestMessage(), this.toDto(c, Collections.emptyMap(), false));
        } catch (Exception ex) {
            this.logger.warn("Model connection {} test failed", connectionId, ex);
            c.setLastTestOk(false);
            c.setLastTestMessage(this.failureReason(ex));
            this.connections.save(c);
            return new ResponseDto(ERROR, c.getLastTestMessage(), this.toDto(c, Collections.emptyMap(), false));
        }
    }

    /** The decrypted key, for a call; null for a keyless provider. */
    public String keyOf(AiModelConnection c) {
        return isNull(c.getApiKey()) ? null : this.encryptionUtil.decrypt(c.getApiKey());
    }

    private ResponseDto validate(AiModelConnectionDto dto) {
        if (isNull(dto.getName()) || dto.getName().trim().isEmpty()) return new ResponseDto(ERROR, "Name missing.");
        if (isNull(dto.getProvider()) || dto.getProvider().trim().isEmpty()) return new ResponseDto(ERROR, "Provider missing.");
        if (isNull(dto.getDefaultModel()) || dto.getDefaultModel().trim().isEmpty()) return new ResponseDto(ERROR, "Default model missing.");
        boolean hasEndpoint = !isNull(dto.getApiEndpoint()) && !dto.getApiEndpoint().trim().isEmpty();
        if (!hasEndpoint && !BUILT_IN_ENDPOINT.contains(dto.getProvider().trim())) {
            return new ResponseDto(ERROR, "This provider needs an API endpoint (only OpenAI, Anthropic and Ollama have a built-in one).");
        }
        if (hasEndpoint) {
            ResponseDto refused = this.endpointPolicy.validateEndpoint(dto.getApiEndpoint());
            if (refused != null) return refused;
        }
        if (TenantContext.isPlatformAdmin() && dto.getConnectionId() == null && dto.getTenantId() == null) {
            return new ResponseDto(ERROR, "Pick the workspace this connection belongs to.");
        }
        return null;
    }

    private String failureReason(Exception ex) {
        String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403")) return "The provider refused the key (HTTP " + m.substring(5, 8) + ").";
        if (m.startsWith("HTTP 404")) return "Nothing answers at that endpoint (HTTP 404).";
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }

    private AiModelConnectionDto toDto(AiModelConnection c, Map<Long, String> tenantNames, boolean withUsage) {
        AiModelConnectionDto d = new AiModelConnectionDto();
        d.setConnectionId(c.getConnectionId()); d.setTenantId(c.getTenantId()); d.setTenantName(tenantNames.get(c.getTenantId()));
        d.setName(c.getName()); d.setProvider(c.getProvider()); d.setApiEndpoint(c.getApiEndpoint());
        d.setApiKeyConfigured(!isNull(c.getApiKey())); d.setDefaultModel(c.getDefaultModel()); d.setIsDefault(c.getIsDefault());
        d.setMaxConcurrency(c.getMaxConcurrency()); d.setDailyTokenBudget(c.getDailyTokenBudget());
        d.setStatus(c.getStatus() == null ? null : c.getStatus().name());
        d.setLastTestedAt(c.getLastTestedAt()); d.setLastTestOk(c.getLastTestOk()); d.setLastTestMessage(c.getLastTestMessage());
        if (!isNull(c.getModelsListed())) d.setModels(this.gson.fromJson(c.getModelsListed(), new TypeToken<List<String>>() {}.getType()));
        d.setDateCreated(c.getDateCreated()); d.setCreatedBy(c.getCreatedBy());
        d.setCreatedByName(c.getCreatedByName()); d.setUpdatedByName(c.getUpdatedByName());
        if (withUsage) {
            d.setPromptCount(this.prompts.countByConnectionIdAndStatusNot(c.getConnectionId(), Status.Delete));
            Timestamp monthAgo = Timestamp.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(30).atStartOfDay());
            Object[] usage = this.runs.usageSince(c.getConnectionId(), monthAgo);
            // A single-row aggregate comes back as one Object[] -- or, with some drivers, an Object[] wrapping it.
            Object[] row = usage != null && usage.length == 1 && usage[0] instanceof Object[] ? (Object[]) usage[0] : usage;
            if (row != null && row.length >= 3) {
                d.setRuns30d(((Number) row[0]).longValue()); d.setTokensIn30d(((Number) row[1]).longValue()); d.setTokensOut30d(((Number) row[2]).longValue());
            }
            d.setTokensToday(this.runs.tokensSince(c.getConnectionId(), Timestamp.valueOf(LocalDate.now(ZoneOffset.UTC).atStartOfDay())));
        }
        return d;
    }
}
