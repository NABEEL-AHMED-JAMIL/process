package process.model.service.impl;

import org.barco.platform.meter.Meter;
import org.barco.platform.meter.RequestUsageKey;
import org.barco.platform.meter.UsageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.ai.AiEndpointPolicy;
import process.ai.AiProviderGateway;
import process.ai.PromptRunner;
import process.billing.MeterClient;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.AiAgentToolDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPrompt;
import process.model.service.AiAgentService;
import process.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * The old "agent" surface, kept over the new rows. An agent was one row bundling a provider
 * connection with one fixed prompt; V44 split it into a model connection and a prompt. The
 * file chat and the job assistant still speak this interface, so it answers with a prompt
 * dressed as an agent: the prompt's id, name, system instructions and tags, the connection's
 * provider, endpoint, key and model. Nothing is written through here any more -- the Prompts
 * and Model connections screens own that.
 */
@Service
public class AiAgentServiceImpl implements AiAgentService {

    private static final int MAX_TEXT_CHARS = 60000;
    private final Logger logger = LoggerFactory.getLogger(AiAgentServiceImpl.class);

    private final AiPromptServiceImpl prompts;
    private final AiModelConnectionServiceImpl connections;
    private final AiProviderGateway gateway;
    private final AiEndpointPolicy endpointPolicy;
    /** The meter, when the console has one; optional so hand-built instances in tests need none. */
    private MeterClient meter;

    public AiAgentServiceImpl(AiPromptServiceImpl prompts, AiModelConnectionServiceImpl connections,
        AiProviderGateway gateway, AiEndpointPolicy endpointPolicy) {
        this.prompts = prompts; this.connections = connections; this.gateway = gateway; this.endpointPolicy = endpointPolicy;
    }

    @Autowired(required = false)
    public void setMeter(MeterClient meter) {
        this.meter = meter;
    }

    @Override
    public ResponseDto fetchAllAgents() throws Exception {
        List<AiAgentDto> rows = this.prompts.visible().stream()
            .filter(p -> p.getStatus() == Status.Active)
            .map(this::asAgent).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Data found.", rows);
    }

    @Override
    public ResponseDto fetchAgentByAgentId(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        Optional<AiPrompt> p = this.prompts.scopedFind(aiAgentId);
        if (!p.isPresent()) return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        return new ResponseDto(SUCCESS, "Data found.", this.asAgent(p.get()));
    }

    /**
     * The prompt behind a tool uuid. Holding the uuid is not authorisation on its own -- a
     * uuid travels the way opaque identifiers do -- so the workspace check every other read
     * makes is made here too, and a refusal reads the same either way.
     */
    @Override
    public ResponseDto fetchToolByUuid(String toolUuid) throws Exception {
        if (isNull(toolUuid) || toolUuid.trim().isEmpty()) return new ResponseDto(ERROR, "toolUuid missing.");
        Optional<AiPrompt> p = this.prompts.scopedFindByUuid(toolUuid).filter(x -> x.getStatus() == Status.Active);
        if (!p.isPresent()) return new ResponseDto(ERROR, "Tool not found or not active.");
        Optional<AiModelConnection> c = this.connectionOf(p.get());
        AiAgentToolDto dto = new AiAgentToolDto();
        dto.setToolUuid(p.get().getPromptUuid());
        dto.setAgentName(p.get().getName());
        dto.setDescription(p.get().getDescription());
        dto.setProvider(c.map(AiModelConnection::getProvider).orElse(null));
        dto.setModel(this.modelOf(p.get(), c));
        dto.setInstructions(p.get().getSystemInstructions());
        dto.setJsonMode("json".equals(p.get().getOutputMode()));
        dto.setTargetFileTypes(p.get().getTags());
        return new ResponseDto(SUCCESS, "Data found.", dto);
    }

    /** What a server-side caller needs to run the prompt's connection: provider, key, model, instructions. */
    @Override
    public ResponseDto resolveRuntimeConfig(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        Optional<AiPrompt> p = this.prompts.scopedFind(aiAgentId);
        if (!p.isPresent()) return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        if (p.get().getStatus() != Status.Active) return new ResponseDto(ERROR, "This agent isn't active.");
        Optional<AiModelConnection> c = this.connectionOf(p.get());
        if (!c.isPresent()) return new ResponseDto(ERROR, "This prompt has no model connection to run on.");
        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider(c.get().getProvider());
        config.setModel(this.modelOf(p.get(), c));
        config.setApiEndpoint(c.get().getApiEndpoint());
        config.setJsonMode("json".equals(p.get().getOutputMode()));
        config.setInstructions(p.get().getSystemInstructions());
        config.setTargetFileTypes(p.get().getTags());
        config.setApiKey(this.connections.keyOf(c.get()));
        return new ResponseDto(SUCCESS, "Resolved.", config);
    }

    /** One call with everything in the request -- the file chat's and the job assistant's path. */
    @Override
    public ResponseDto processAdHoc(AdHocPromptRequestDto dto) throws Exception {
        ResponseDto refused = this.validateAdHoc(dto);
        if (refused != null) return refused;
        String text = dto.getText().length() > MAX_TEXT_CHARS ? dto.getText().substring(0, MAX_TEXT_CHARS) : dto.getText();
        try {
            AiProviderGateway.ChatRequest req = new AiProviderGateway.ChatRequest();
            req.provider = dto.getProvider(); req.apiKey = dto.getApiKey(); req.apiEndpoint = dto.getApiEndpoint();
            req.model = dto.getModel(); req.system = dto.getInstructions(); req.user = text;
            req.jsonMode = Boolean.TRUE.equals(dto.getJsonMode());
            AiProviderGateway.ChatAnswer chat = this.gateway.chat(req);
            this.metered(chat, req.model);
            String answer = chat.text;
            if (req.jsonMode) answer = PromptRunner.stripFencesPublic(answer);
            return new ResponseDto(SUCCESS, "Processed successfully.", answer);
        } catch (Exception ex) {
            // The detail goes to the log, not to the caller: it carries the provider's own body.
            this.logger.error("An error occurred while calling the AI provider (ad-hoc, provider={})", dto.getProvider(), ex);
            return new ResponseDto(ERROR, "The AI provider request failed.");
        }
    }

    /**
     * What an ad-hoc call spent, told to the meter (MIG-198, the owner's decision): the file chat and the job
     * assistant are real model spend, metered as prompt runs are. The key is the request's correlation id and
     * this call's place in the request -- a replayed request meters once, two calls in one request twice.
     * A provider that reports no usage (-1) is not guessed at.
     */
    private void metered(AiProviderGateway.ChatAnswer chat, String model) {
        Long tenantId = TenantContext.getTenantId();
        if (this.meter == null || tenantId == null) {
            return;
        }
        if (chat.tokensIn < 0 && chat.tokensOut < 0) {
            this.logger.warn("ad-hoc model call for workspace {} on {}: the provider reported no usage, so none is metered", tenantId, model);
            return;
        }
        String key = RequestUsageKey.next("ai-adhoc");
        if (chat.tokensIn > 0) {
            this.meter.report(UsageEvent.of(tenantId, Meter.AI_TOKENS_IN, chat.tokensIn, key + "#in")
                .subject("ad-hoc", model).actor(TenantContext.getAppUserId()).source("console").note(model));
        }
        if (chat.tokensOut > 0) {
            this.meter.report(UsageEvent.of(tenantId, Meter.AI_TOKENS_OUT, chat.tokensOut, key + "#out")
                .subject("ad-hoc", model).actor(TenantContext.getAppUserId()).source("console").note(model));
        }
    }

    private ResponseDto validateAdHoc(AdHocPromptRequestDto dto) {
        if (isNull(dto.getProvider()) || dto.getProvider().trim().isEmpty()) return new ResponseDto(ERROR, "provider missing.");
        boolean builtIn = "OpenAI".equals(dto.getProvider()) || "Anthropic".equals(dto.getProvider()) || "Ollama".equals(dto.getProvider());
        if (!builtIn && (isNull(dto.getApiEndpoint()) || dto.getApiEndpoint().trim().isEmpty())) {
            return new ResponseDto(ERROR, "This provider requires an apiEndpoint (only OpenAI/Anthropic/Ollama have a built-in one).");
        }
        if (!"Ollama".equals(dto.getProvider()) && (isNull(dto.getApiKey()) || dto.getApiKey().trim().isEmpty())) {
            return new ResponseDto(ERROR, "apiKey missing (required unless provider is Ollama).");
        }
        if (isNull(dto.getModel()) || dto.getModel().trim().isEmpty()) return new ResponseDto(ERROR, "model missing.");
        if (isNull(dto.getInstructions()) || dto.getInstructions().trim().isEmpty()) return new ResponseDto(ERROR, "instructions (prompt) missing.");
        if (isNull(dto.getText()) || dto.getText().trim().isEmpty()) return new ResponseDto(ERROR, "text missing -- nothing to process.");
        // OpenAI and Anthropic ignore whatever endpoint arrives; only the others dial the one in the request.
        boolean usesRequestedEndpoint = !"OpenAI".equals(dto.getProvider()) && !"Anthropic".equals(dto.getProvider());
        if (usesRequestedEndpoint && !isNull(dto.getApiEndpoint()) && !dto.getApiEndpoint().trim().isEmpty()) {
            return this.endpointPolicy.validateEndpoint(dto.getApiEndpoint());
        }
        return null;
    }

    private Optional<AiModelConnection> connectionOf(AiPrompt p) {
        return p.getConnectionId() != null ? this.connections.scopedFind(p.getConnectionId()) : this.connections.defaultFor(p.getTenantId());
    }

    private String modelOf(AiPrompt p, Optional<AiModelConnection> c) {
        return !isNull(p.getModel()) ? p.getModel() : c.map(AiModelConnection::getDefaultModel).orElse(null);
    }

    private AiAgentDto asAgent(AiPrompt p) {
        Optional<AiModelConnection> c = this.connectionOf(p);
        AiAgentDto dto = new AiAgentDto();
        dto.setAiAgentId(p.getPromptId());
        dto.setAgentName(p.getName());
        dto.setDescription(p.getDescription());
        dto.setProvider(c.map(AiModelConnection::getProvider).orElse(null));
        dto.setApiEndpoint(c.map(AiModelConnection::getApiEndpoint).orElse(null));
        dto.setApiKeyConfigured(c.map(x -> !isNull(x.getApiKey())).orElse(false));
        dto.setModel(this.modelOf(p, c));
        dto.setTargetFileTypes(p.getTags());
        dto.setInstructions(p.getSystemInstructions());
        dto.setJsonMode("json".equals(p.getOutputMode()));
        dto.setStatus(p.getStatus());
        dto.setDateCreated(p.getDateCreated());
        dto.setToolUuid(p.getPromptUuid());
        return dto;
    }
}
