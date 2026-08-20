package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

@Service
public class AiAgentServiceImpl implements AiAgentService {

    private Logger logger = LoggerFactory.getLogger(AiAgentServiceImpl.class);

    private static final int MAX_TEXT_CHARS = 60000;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String OLLAMA_DEFAULT_BASE_URL = "http://host.docker.internal:11434";

    private final AiAgentRepository aiAgentRepository;
    private final EncryptionUtil encryptionUtil;
    private final TenantFilterHelper tenantFilterHelper;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))

        .readTimeout(10, TimeUnit.MINUTES)
        .build();

    @PersistenceContext
    private EntityManager entityManager;

    public AiAgentServiceImpl(AiAgentRepository aiAgentRepository, EncryptionUtil encryptionUtil,
        TenantFilterHelper tenantFilterHelper) {
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
        return new ResponseDto(SUCCESS, "Data found.",
            aiAgents.stream().map(this::ensureToolUuid).map(this::getAiAgentDto).collect(Collectors.toList()));
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

    @Override
    public ResponseDto fetchToolByUuid(String toolUuid) throws Exception {
        if (isNull(toolUuid) || toolUuid.trim().isEmpty()) {
            return new ResponseDto(ERROR, "toolUuid missing.");
        }
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findByToolUuid(toolUuid.trim());
        if (!aiAgentOpt.isPresent() || aiAgentOpt.get().getStatus() != Status.Active) {
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
            this.logger.error("An error occurred while calling the AI provider (ad-hoc, provider={}): {}",
                dto.getProvider(), ex.getMessage());
            return new ResponseDto(ERROR, "The AI provider request failed: " + ex.getMessage());
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
        return null;
    }

    private String callProvider(String provider, String apiKey, String apiEndpoint, String model,
        String instructions, boolean jsonMode, String userMessage) throws Exception {

        if ("OpenAI".equals(provider)) {
            return this.callOpenAi(apiKey, model, instructions, userMessage);
        } else if ("Anthropic".equals(provider)) {
            return this.callAnthropic(apiKey, model, instructions, userMessage);
        } else if ("Ollama".equals(provider)) {
            return this.callOllama(apiEndpoint, model, instructions, jsonMode, userMessage);
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
