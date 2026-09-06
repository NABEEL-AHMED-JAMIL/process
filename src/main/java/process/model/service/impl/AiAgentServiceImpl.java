package process.model.service.impl;

import com.google.gson.Gson;
import process.util.UserNameResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.AiAgentToolDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiAgent;
import process.model.repository.AiAgentRepository;
import process.model.service.AiAgentService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class AiAgentServiceImpl implements AiAgentService {

    private Logger logger = LoggerFactory.getLogger(AiAgentServiceImpl.class);

    private static final int MAX_TEXT_CHARS = 60000;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String OLLAMA_DEFAULT_BASE_URL = "http://host.docker.internal:11434";

    /** Said the same way whichever rule refused, so the answer carries no map of the network. */
    private static final String ENDPOINT_NOT_ALLOWED =
        "That apiEndpoint is not an allowed AI provider address.";

    private final AiAgentRepository aiAgentRepository;
    private final EncryptionUtil encryptionUtil;
    private final TenantFilterHelper tenantFilterHelper;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))

        .readTimeout(10, TimeUnit.MINUTES)
        .build();

    /**
     * Hosts the server will call over a private address or over plain http.
     *
     * Everything else has to be a public https host. The default is the one internal name the
     * server already reaches by itself for Ollama, so trusting it grants nothing that
     * OLLAMA_DEFAULT_BASE_URL did not already grant; a deployment whose model server lives
     * somewhere else names that host here rather than the check being loosened for everybody.
     */
    @Value("${ai.allowed-endpoint-hosts:host.docker.internal}")
    private String allowedEndpointHosts;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserNameResolver userNameResolver;


    public AiAgentServiceImpl(AiAgentRepository aiAgentRepository, EncryptionUtil encryptionUtil,
        TenantFilterHelper tenantFilterHelper,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.aiAgentRepository = aiAgentRepository;
        this.encryptionUtil = encryptionUtil;
        this.tenantFilterHelper = tenantFilterHelper;
    }

    private boolean isOwnedByCaller(AiAgent aiAgent) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return aiAgent != null && Objects.equals(aiAgent.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    @Transactional
    public ResponseDto addAgent(AiAgentDto aiAgentDto) throws Exception {
        ResponseDto validationError = this.validateAgent(aiAgentDto);
        if (validationError != null) {
            return validationError;
        }
        AiAgent aiAgent = new AiAgent();
        aiAgent.setTenantId(TenantContext.getTenantId());
        this.applyAgentDto(aiAgent, aiAgentDto);
        aiAgent.setStatus(Status.Active);
        aiAgent.setDateCreated(new Timestamp(System.currentTimeMillis()));
        aiAgent.setToolUuid(UUID.randomUUID().toString());
        aiAgent = this.aiAgentRepository.save(aiAgent);
        return new ResponseDto(SUCCESS, String.format("Agent saved with %s.", aiAgent.getAiAgentId()),
            this.getAiAgentDto(aiAgent));
    }

    @Override
    @Transactional
    public ResponseDto updateAgent(AiAgentDto aiAgentDto) throws Exception {
        if (isNull(aiAgentDto.getAiAgentId())) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        ResponseDto validationError = this.validateAgent(aiAgentDto);
        if (validationError != null) {
            return validationError;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentDto.getAiAgentId());
        if (!aiAgentOpt.isPresent() || !this.isOwnedByCaller(aiAgentOpt.get())) {
            return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentDto.getAiAgentId()));
        }
        AiAgent aiAgent = aiAgentOpt.get();
        this.applyAgentDto(aiAgent, aiAgentDto);
        if (!isNull(aiAgentDto.getStatus())) {
            aiAgent.setStatus(aiAgentDto.getStatus());
        }
        this.aiAgentRepository.save(aiAgent);
        return new ResponseDto(SUCCESS, String.format("Agent saved with %s.", aiAgent.getAiAgentId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteAgent(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentId);
        if (!aiAgentOpt.isPresent() || !this.isOwnedByCaller(aiAgentOpt.get())) {
            return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        }
        AiAgent aiAgent = aiAgentOpt.get();
        aiAgent.setStatus(Status.Delete);
        this.aiAgentRepository.save(aiAgent);
        return new ResponseDto(SUCCESS, String.format("Agent deleted with %s.", aiAgentId));
    }

    @Override
    @Transactional
    public ResponseDto fetchAllAgents() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<AiAgent> aiAgents = this.aiAgentRepository.findByStatusNotOrderByAiAgentIdDesc(Status.Delete);
        List<AiAgentDto> agentDtos = aiAgents.stream().map(this::ensureToolUuid)
            .map(this::getAiAgentDto).collect(Collectors.toList());
        this.userNameResolver.attachToDtos(agentDtos, this.aiAgentRepository, AiAgent::getAiAgentId);
        return new ResponseDto(SUCCESS, "Data found.", agentDtos);
    }

    @Override
    @Transactional
    public ResponseDto fetchAgentByAgentId(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentId);
        if (!aiAgentOpt.isPresent() || !this.isOwnedByCaller(aiAgentOpt.get())) {
            return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.getAiAgentDto(this.ensureToolUuid(aiAgentOpt.get())));
    }

    /**
     * The agent behind a tool uuid.
     *
     * Holding the uuid is not authorisation on its own. An agent's instructions are the tenant's
     * own prompt engineering, and a uuid travels the way opaque identifiers do -- through a
     * shared link, a proxy log, a support ticket -- so the tenant check every other read here
     * makes is made here too. A refusal reads the same either way, so it never confirms that
     * somebody else's uuid exists.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchToolByUuid(String toolUuid) throws Exception {
        if (isNull(toolUuid) || toolUuid.trim().isEmpty()) {
            return new ResponseDto(ERROR, "toolUuid missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findByToolUuid(toolUuid.trim());
        if (!aiAgentOpt.isPresent() || aiAgentOpt.get().getStatus() != Status.Active
            || !this.isOwnedByCaller(aiAgentOpt.get())) {
            return new ResponseDto(ERROR, "Tool not found or not active.");
        }
        AiAgent aiAgent = aiAgentOpt.get();
        AiAgentToolDto dto = new AiAgentToolDto();
        dto.setToolUuid(aiAgent.getToolUuid());
        dto.setAgentName(aiAgent.getAgentName());
        dto.setDescription(aiAgent.getDescription());
        dto.setProvider(aiAgent.getProvider());
        dto.setModel(aiAgent.getModel());
        dto.setInstructions(aiAgent.getInstructions());
        dto.setJsonMode(aiAgent.getJsonMode());
        dto.setTargetFileTypes(aiAgent.getTargetFileTypes());
        return new ResponseDto(SUCCESS, "Data found.", dto);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto resolveRuntimeConfig(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentId);
        if (!aiAgentOpt.isPresent() || !this.isOwnedByCaller(aiAgentOpt.get())) {
            return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        }
        AiAgent aiAgent = aiAgentOpt.get();
        if (aiAgent.getStatus() != Status.Active) {
            return new ResponseDto(ERROR, "This agent isn't active.");
        }
        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider(aiAgent.getProvider());
        config.setModel(aiAgent.getModel());
        config.setApiEndpoint(aiAgent.getApiEndpoint());
        config.setJsonMode(aiAgent.getJsonMode());
        // The whole point of a per-agent row: instructions live in this table so an admin can
        // change how an agent behaves without a deploy. This DTO used to stop at provider/model/
        // key/endpoint, so nothing that called resolveRuntimeConfig could ever see them --
        // FileChatServiceImpl built its own hardcoded prompt regardless of which agent was picked.
        config.setInstructions(aiAgent.getInstructions());
        config.setTargetFileTypes(aiAgent.getTargetFileTypes());
        if (!isNull(aiAgent.getApiKey())) {
            config.setApiKey(this.encryptionUtil.decrypt(aiAgent.getApiKey()));
        }
        return new ResponseDto(SUCCESS, "Resolved.", config);
    }

    @Override
    public ResponseDto processAdHoc(AdHocPromptRequestDto dto) throws Exception {
        ResponseDto validationError = this.validateAdHoc(dto);
        if (validationError != null) {
            return validationError;
        }
        String text = dto.getText();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        try {
            String resultText = this.callProvider(dto.getProvider(), dto.getApiKey(), dto.getApiEndpoint(),
                dto.getModel(), dto.getInstructions(), Boolean.TRUE.equals(dto.getJsonMode()), text);
            if (Boolean.TRUE.equals(dto.getJsonMode())) {
                resultText = this.stripJsonCodeFences(resultText);
            }
            return new ResponseDto(SUCCESS, "Processed successfully.", resultText);
        } catch (Exception ex) {
            // The detail goes to the log, not to the caller. It carries the provider's own
            // response body, which for anything other than a real model server is whatever the
            // server managed to read at that address.
            this.logger.error("An error occurred while calling the AI provider (ad-hoc, provider={})",
                dto.getProvider(), ex);
            return new ResponseDto(ERROR, "The AI provider request failed.");
        }
    }

    private ResponseDto validateAdHoc(AdHocPromptRequestDto dto) {
        if (isNull(dto.getProvider()) || dto.getProvider().trim().isEmpty()) {
            return new ResponseDto(ERROR, "provider missing.");
        }
        boolean isBuiltInProvider = "OpenAI".equals(dto.getProvider())
            || "Anthropic".equals(dto.getProvider())
            || "Ollama".equals(dto.getProvider());
        if (!isBuiltInProvider &&
            (isNull(dto.getApiEndpoint()) || dto.getApiEndpoint().trim().isEmpty())) {
            return new ResponseDto(ERROR, "This provider requires an apiEndpoint (only OpenAI/Anthropic/Ollama have a built-in one).");
        }

        if (!"Ollama".equals(dto.getProvider()) && (isNull(dto.getApiKey()) || dto.getApiKey().trim().isEmpty())) {
            return new ResponseDto(ERROR, "apiKey missing (required unless provider is Ollama).");
        }
        if (isNull(dto.getModel()) || dto.getModel().trim().isEmpty()) {
            return new ResponseDto(ERROR, "model missing.");
        }
        if (isNull(dto.getInstructions()) || dto.getInstructions().trim().isEmpty()) {
            return new ResponseDto(ERROR, "instructions (prompt) missing.");
        }
        if (isNull(dto.getText()) || dto.getText().trim().isEmpty()) {
            return new ResponseDto(ERROR, "text missing -- nothing to process.");
        }
        // OpenAI and Anthropic ignore whatever endpoint arrives; the URL they are called on is a
        // constant below. Only Ollama and the generic providers dial the one in the request, and
        // a blank one means the server's own default rather than anything the caller chose.
        boolean usesRequestedEndpoint = !"OpenAI".equals(dto.getProvider())
            && !"Anthropic".equals(dto.getProvider());
        if (usesRequestedEndpoint && !isNull(dto.getApiEndpoint()) && !dto.getApiEndpoint().trim().isEmpty()) {
            return this.validateEndpoint(dto.getApiEndpoint());
        }
        return null;
    }

    /**
     * Whether the server is willing to make a request to this address on the caller's behalf.
     *
     * The endpoint arrives in the request body and the provider's answer is handed back, so
     * unchecked it is a way to read whatever the application server can reach and the caller
     * cannot -- an internal admin page, a cloud metadata service, or one port at a time until
     * something answers. A public https host is the intended use; a private address or plain
     * http has to be a host the operator named in ai.allowed-endpoint-hosts.
     *
     * Every address the name resolves to is checked, not just the first, since a name that
     * resolves to both a public and a private address would otherwise pass on the public one.
     *
     * Returns null when the endpoint may be called.
     */
    private ResponseDto validateEndpoint(String apiEndpoint) {
        URI uri;
        try {
            uri = new URI(apiEndpoint.trim());
        } catch (URISyntaxException ex) {
            return new ResponseDto(ERROR, "apiEndpoint is not a valid URL.");
        }
        String scheme = isNull(uri.getScheme()) ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (isNull(host) || host.trim().isEmpty()
            || (!"http".equals(scheme) && !"https".equals(scheme))) {
            return new ResponseDto(ERROR, "apiEndpoint must be an http or https URL naming a host.");
        }
        if (this.allowedHosts().contains(host.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (!"https".equals(scheme)) {
            return new ResponseDto(ERROR, ENDPOINT_NOT_ALLOWED);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            // A name the server cannot resolve is not a provider it can call either way.
            return new ResponseDto(ERROR, ENDPOINT_NOT_ALLOWED);
        }
        for (int i = 0; i < addresses.length; i++) {
            if (isInternalAddress(addresses[i])) {
                return new ResponseDto(ERROR, ENDPOINT_NOT_ALLOWED);
            }
        }
        return null;
    }

    private Set<String> allowedHosts() {
        Set<String> hosts = new HashSet<>();
        if (isNull(this.allowedEndpointHosts) || this.allowedEndpointHosts.trim().isEmpty()) {
            return hosts;
        }
        for (String host : this.allowedEndpointHosts.split(",")) {
            if (!host.trim().isEmpty()) {
                hosts.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    /**
     * Whether an address belongs to the network rather than the internet.
     *
     * InetAddress covers loopback, link-local, the IPv4 private ranges and multicast; the two
     * added by hand are carrier-grade NAT and the IPv6 unique-local range, which are private in
     * practice and which it does not classify.
     */
    private static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isAnyLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        return octets.length == 16 && (octets[0] & 0xFE) == 0xFC;
    }

    private String callProvider(String provider, String apiKey, String apiEndpoint, String model,
        String instructions, boolean jsonMode, String userMessage) throws Exception {

        if ("OpenAI".equals(provider)) {
            return this.callOpenAi(apiKey, model, instructions, userMessage);
        } else if ("Anthropic".equals(provider)) {
            return this.callAnthropic(apiKey, model, instructions, userMessage);
        } else if ("Ollama".equals(provider)) {
            return this.callOllama(apiEndpoint, model, instructions, jsonMode, userMessage);
        } else if ("AzureOpenAI".equals(provider)) {
            return this.callAzureOpenAi(apiKey, apiEndpoint, model, instructions, userMessage);
        }
        return this.callGenericOpenAiCompatible(provider, apiKey, apiEndpoint, model, instructions, userMessage);
    }

    private String callOllama(String apiEndpoint, String model, String instructions, boolean jsonMode, String userMessage) throws Exception {
        String baseUrl = isNull(apiEndpoint) || apiEndpoint.trim().isEmpty()
            ? OLLAMA_DEFAULT_BASE_URL : apiEndpoint.trim().replaceAll("/+$", "");
        JsonArray messages = new JsonArray();
        messages.add(this.chatMessage("system", instructions));
        messages.add(this.chatMessage("user", userMessage));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);

        body.addProperty("keep_alive", "30m");

        if (jsonMode) {
            body.addProperty("format", "json");
        }

        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", 16384);
        body.add("options", options);
        Request request = new Request.Builder()
            .url(baseUrl + "/api/chat")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        JsonObject response = this.execute(request);
        return response.getAsJsonObject("message").get("content").getAsString();
    }

    private String callOpenAi(String apiKey, String model, String instructions, String userMessage) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(this.chatMessage("system", instructions));
        messages.add(this.chatMessage("user", userMessage));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        JsonObject response = this.execute(request);
        return response.getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString();
    }

    /**
     * Azure OpenAI is not the generic OpenAI-compatible path with a different hostname -- it
     * genuinely rejects the request `callGenericOpenAiCompatible` would send. Azure's Chat
     * Completions API authenticates a plain resource key through an `api-key` header;
     * `Authorization: Bearer` is only accepted there for Azure AD OAuth tokens, which is a
     * separate setup almost nobody has for a simple integration. Sent through the generic path,
     * an Azure OpenAI agent configured the ordinary way (a resource key, not an AD token) failed
     * authentication on every single request -- silently, since the generic path's only
     * validation is that an endpoint was supplied at all.
     *
     * The body shape is otherwise identical to plain OpenAI's, since Azure's Chat Completions
     * API is the same `messages` array format. There is no sensible default endpoint the way
     * api.openai.com is for OpenAI -- an Azure deployment URL is tenant- and deployment-specific
     * (`https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions
     * ?api-version=...`) -- so apiEndpoint stays required here exactly as it already is for
     * every other non-native provider.
     */
    private String callAzureOpenAi(String apiKey, String apiEndpoint, String model,
        String instructions, String userMessage) throws Exception {
        if (isNull(apiEndpoint) || apiEndpoint.trim().isEmpty()) {
            throw new IllegalStateException(
                "Azure OpenAI needs the full deployment URL as its API endpoint "
                    + "(https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=...).");
        }
        JsonArray messages = new JsonArray();
        messages.add(this.chatMessage("system", instructions));
        messages.add(this.chatMessage("user", userMessage));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        Request request = new Request.Builder()
            .url(apiEndpoint.trim())
            .header("api-key", apiKey)
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        JsonObject response = this.execute(request);
        return response.getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString();
    }

    private String callAnthropic(String apiKey, String model, String instructions, String userMessage) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(this.chatMessage("user", userMessage));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("system", instructions);
        body.addProperty("max_tokens", 4096);
        body.add("messages", messages);
        Request request = new Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        JsonObject response = this.execute(request);
        return response.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private String callGenericOpenAiCompatible(String provider, String apiKey, String apiEndpoint, String model,
        String instructions, String userMessage) throws Exception {
        if (isNull(apiEndpoint)) {
            throw new IllegalStateException(
                "Provider \"" + provider + "\" has no apiEndpoint configured.");
        }
        JsonArray messages = new JsonArray();
        messages.add(this.chatMessage("system", instructions));
        messages.add(this.chatMessage("user", userMessage));
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        Request request = new Request.Builder()
            .url(apiEndpoint)
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        JsonObject response = this.execute(request);
        return response.getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString();
    }

    private String stripJsonCodeFences(String text) {
        if (isNull(text)) {
            return text;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            trimmed = firstNewline >= 0 ? trimmed.substring(firstNewline + 1) : trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private JsonObject chatMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private JsonObject execute(Request request) throws Exception {
        try (Response response = this.httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException(String.format("HTTP %d: %s", response.code(), responseBody));
            }
            return this.gson.fromJson(responseBody, JsonObject.class);
        }
    }

    private ResponseDto validateAgent(AiAgentDto aiAgentDto) {
        if (isNull(aiAgentDto.getAgentName()) || aiAgentDto.getAgentName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent agentName missing.");
        }
        if (isNull(aiAgentDto.getProvider()) || aiAgentDto.getProvider().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent provider missing.");
        }

        boolean isBuiltInProvider = "OpenAI".equals(aiAgentDto.getProvider())
            || "Anthropic".equals(aiAgentDto.getProvider())
            || "Ollama".equals(aiAgentDto.getProvider());
        if (!isBuiltInProvider &&
            (isNull(aiAgentDto.getApiEndpoint()) || aiAgentDto.getApiEndpoint().trim().isEmpty())) {
            return new ResponseDto(ERROR, "This provider requires an apiEndpoint (only OpenAI/Anthropic/Ollama have a built-in one).");
        }
        if (isNull(aiAgentDto.getModel()) || aiAgentDto.getModel().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent model missing.");
        }
        if (isNull(aiAgentDto.getTargetFileTypes()) || aiAgentDto.getTargetFileTypes().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent targetFileTypes missing.");
        }
        if (isNull(aiAgentDto.getInstructions()) || aiAgentDto.getInstructions().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent instructions missing.");
        }
        return null;
    }

    private void applyAgentDto(AiAgent aiAgent, AiAgentDto dto) {
        aiAgent.setAgentName(dto.getAgentName());
        aiAgent.setDescription(dto.getDescription());
        aiAgent.setProvider(dto.getProvider());
        aiAgent.setApiEndpoint(dto.getApiEndpoint());
        if (!isNull(dto.getApiKey()) && !dto.getApiKey().trim().isEmpty()) {
            aiAgent.setApiKey(this.encryptionUtil.encrypt(dto.getApiKey()));
        }
        aiAgent.setModel(dto.getModel());
        aiAgent.setTargetFileTypes(dto.getTargetFileTypes());
        aiAgent.setInstructions(dto.getInstructions());
        aiAgent.setJsonMode(Boolean.TRUE.equals(dto.getJsonMode()));
    }

    private AiAgentDto getAiAgentDto(AiAgent aiAgent) {
        AiAgentDto dto = new AiAgentDto();
        dto.setAiAgentId(aiAgent.getAiAgentId());
        dto.setAgentName(aiAgent.getAgentName());
        dto.setDescription(aiAgent.getDescription());
        dto.setProvider(aiAgent.getProvider());
        dto.setApiEndpoint(aiAgent.getApiEndpoint());
        dto.setApiKeyConfigured(!isNull(aiAgent.getApiKey()));
        dto.setModel(aiAgent.getModel());
        dto.setTargetFileTypes(aiAgent.getTargetFileTypes());
        dto.setInstructions(aiAgent.getInstructions());
        dto.setJsonMode(aiAgent.getJsonMode());
        dto.setStatus(aiAgent.getStatus());
        dto.setDateCreated(aiAgent.getDateCreated());
        dto.setToolUuid(aiAgent.getToolUuid());
        return dto;
    }

    private AiAgent ensureToolUuid(AiAgent aiAgent) {
        if (isNull(aiAgent.getToolUuid()) || aiAgent.getToolUuid().trim().isEmpty()) {
            aiAgent.setToolUuid(UUID.randomUUID().toString());
            aiAgent = this.aiAgentRepository.save(aiAgent);
        }
        return aiAgent;
    }

}
