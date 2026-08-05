package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
import process.model.dto.AiAgentToolDto;
import process.model.dto.ProcessTextRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiAgent;
import process.model.repository.AiAgentRepository;
import process.model.service.AiAgentService;
import process.util.EncryptionUtil;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Service
public class AiAgentServiceImpl implements AiAgentService {

    private Logger logger = LoggerFactory.getLogger(AiAgentServiceImpl.class);

    /** Any single file's extracted text is capped here before being sent to the provider --
     * keeps requests within typical context-window/cost limits regardless of source file size. */
    private static final int MAX_TEXT_CHARS = 60000;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Ollama runs as a local Docker container with no auth -- reachable from the process
     * container via the Docker Desktop host alias. Overridable per-agent via apiEndpoint. */
    private static final String OLLAMA_DEFAULT_BASE_URL = "http://host.docker.internal:11434";

    private final AiAgentRepository aiAgentRepository;
    private final EncryptionUtil encryptionUtil;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        // local CPU inference (e.g. Ollama) can take much longer than a hosted API, especially
        // when the model was idle and Ollama has to reload it from disk before generating
        .readTimeout(10, TimeUnit.MINUTES)
        .build();

    public AiAgentServiceImpl(AiAgentRepository aiAgentRepository, EncryptionUtil encryptionUtil) {
        this.aiAgentRepository = aiAgentRepository;
        this.encryptionUtil = encryptionUtil;
    }

    /**
     * Method use to add a new AI agent
     * @param aiAgentDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto addAgent(AiAgentDto aiAgentDto) throws Exception {
        ResponseDto validationError = this.validateAgent(aiAgentDto);
        if (validationError != null) {
            return validationError;
        }
        AiAgent aiAgent = new AiAgent();
        this.applyAgentDto(aiAgent, aiAgentDto);
        aiAgent.setStatus(Status.Active);
        aiAgent.setDateCreated(new Timestamp(System.currentTimeMillis()));
        aiAgent.setToolUuid(UUID.randomUUID().toString());
        aiAgent = this.aiAgentRepository.save(aiAgent);
        return new ResponseDto(SUCCESS, String.format("Agent saved with %s.", aiAgent.getAiAgentId()),
            this.getAiAgentDto(aiAgent));
    }

    /**
     * Method use to update an existing AI agent -- apiKey is only overwritten when a new
     * (non-empty) value is supplied, so callers can update other fields without re-sending
     * (and re-encrypting) an unchanged key.
     * @param aiAgentDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto updateAgent(AiAgentDto aiAgentDto) throws Exception {
        if (isNull(aiAgentDto.getAiAgentId())) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        ResponseDto validationError = this.validateAgent(aiAgentDto);
        if (validationError != null) {
            return validationError;
        }
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentDto.getAiAgentId());
        if (!aiAgentOpt.isPresent()) {
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

    /**
     * Method use to soft-delete an AI agent
     * @param aiAgentId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto deleteAgent(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentId);
        if (!aiAgentOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        }
        AiAgent aiAgent = aiAgentOpt.get();
        aiAgent.setStatus(Status.Delete);
        this.aiAgentRepository.save(aiAgent);
        return new ResponseDto(SUCCESS, String.format("Agent deleted with %s.", aiAgentId));
    }

    /**
     * Method use to fetch every non-deleted AI agent
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchAllAgents() throws Exception {
        List<AiAgent> aiAgents = this.aiAgentRepository.findByStatusNotOrderByAiAgentIdDesc(Status.Delete);
        return new ResponseDto(SUCCESS, "Data found.",
            aiAgents.stream().map(this::ensureToolUuid).map(this::getAiAgentDto).collect(Collectors.toList()));
    }

    /**
     * Method use to fetch a single AI agent by id
     * @param aiAgentId
     * @return ResponseDto
     * */
    @Override
    public ResponseDto fetchAgentByAgentId(Long aiAgentId) throws Exception {
        if (isNull(aiAgentId)) {
            return new ResponseDto(ERROR, "Agent aiAgentId missing.");
        }
        Optional<AiAgent> aiAgentOpt = this.aiAgentRepository.findById(aiAgentId);
        if (!aiAgentOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Agent not found with %d.", aiAgentId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.getAiAgentDto(this.ensureToolUuid(aiAgentOpt.get())));
    }

    /**
     * Method use to fetch the public-safe configuration (no apiKey/apiEndpoint) for a single
     * agent by its toolUuid -- this is what a "Copy Tool URL" link resolves to, meant for an
     * external consumer (e.g. a Source Task XML payload) to read before calling processText
     * with the same toolUuid to actually run the agent. Only Active agents are resolvable here.
     * @param toolUuid
     * @return ResponseDto
     * */
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

    /**
     * Method use to run an agent's instructions against a piece of already-extracted text
     * (the frontend does PDF/CSV/etc. extraction before calling this) and return the
     * provider's response -- nothing here is persisted, the result is purely returned to the caller.
     * @param processTextRequestDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto processText(ProcessTextRequestDto processTextRequestDto) throws Exception {
        boolean hasUuid = !isNull(processTextRequestDto.getAiAgentUuid())
            && !processTextRequestDto.getAiAgentUuid().trim().isEmpty();
        if (isNull(processTextRequestDto.getAiAgentId()) && !hasUuid) {
            return new ResponseDto(ERROR, "aiAgentId or aiAgentUuid missing.");
        }
        if (isNull(processTextRequestDto.getText()) || processTextRequestDto.getText().trim().isEmpty()) {
            return new ResponseDto(ERROR, "No text extracted from the file to process.");
        }
        // aiAgentId (internal callers, e.g. the frontend) takes priority when both are supplied;
        // aiAgentUuid is the lookup an external consumer uses, since it only ever sees the
        // public toolUuid from a "Copy Tool URL" link, never the internal sequence id.
        Optional<AiAgent> aiAgentOpt = !isNull(processTextRequestDto.getAiAgentId())
            ? this.aiAgentRepository.findById(processTextRequestDto.getAiAgentId())
            : this.aiAgentRepository.findByToolUuid(processTextRequestDto.getAiAgentUuid().trim());
        if (!aiAgentOpt.isPresent()) {
            return new ResponseDto(ERROR, !isNull(processTextRequestDto.getAiAgentId())
                ? String.format("Agent not found with %d.", processTextRequestDto.getAiAgentId())
                : "Agent not found for the given aiAgentUuid.");
        }
        AiAgent aiAgent = aiAgentOpt.get();
        if (aiAgent.getStatus() != Status.Active) {
            return new ResponseDto(ERROR, "This agent is not active.");
        }
        // Ollama is local/unauthenticated -- every other provider needs a configured key
        if (!"Ollama".equals(aiAgent.getProvider()) && isNull(aiAgent.getApiKey())) {
            return new ResponseDto(ERROR, "This agent has no API key configured yet -- edit the agent and add one.");
        }
        String text = processTextRequestDto.getText();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        String apiKey = isNull(aiAgent.getApiKey()) ? null : this.encryptionUtil.decrypt(aiAgent.getApiKey());
        String fileLabel = isNull(processTextRequestDto.getFileName()) ? "the file" : processTextRequestDto.getFileName();
        String userMessage = String.format("File: %s\n\nContent:\n%s", fileLabel, text);
        // an explicit instructions override lets the caller reuse this agent's provider/model/
        // apiKey with a one-off prompt instead of the agent's saved instructions
        boolean hasOverride = !isNull(processTextRequestDto.getInstructions())
            && !processTextRequestDto.getInstructions().trim().isEmpty();
        String instructions = hasOverride ? processTextRequestDto.getInstructions() : aiAgent.getInstructions();
        try {
            String resultText = this.callProvider(aiAgent.getProvider(), apiKey, aiAgent.getApiEndpoint(),
                aiAgent.getModel(), instructions, Boolean.TRUE.equals(aiAgent.getJsonMode()), userMessage);
            if (Boolean.TRUE.equals(aiAgent.getJsonMode())) {
                resultText = this.stripJsonCodeFences(resultText);
            }
            return new ResponseDto(SUCCESS, "Processed successfully.", resultText);
        } catch (Exception ex) {
            this.logger.error("An error occurred while calling the AI provider for agentId {}: {}",
                aiAgent.getAiAgentId(), ex.getMessage());
            return new ResponseDto(ERROR, "The AI provider request failed: " + ex.getMessage());
        }
    }

    /**
     * Method use to run a fully ad-hoc provider+model+prompt+text call, not tied to any saved
     * AiAgent -- the caller supplies everything (including the API key, used in-memory for
     * this call only) each time. Nothing here is persisted.
     * @param dto
     * @return ResponseDto
     * */
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

    /**
     * Method use to validate an ad-hoc prompt payload -- mirrors validateAgent() minus the
     * DB-only fields (agentName, targetFileTypes), applied directly against the request.
     * @param dto
     * @return ResponseDto or null when valid
     * */
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
        // Ollama is local/unauthenticated -- every other provider needs a key supplied
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

    /**
     * Method use to dispatch to the right request/response shape for the configured provider --
     * shared by both the saved-agent path (processText) and the ad-hoc path (processAdHoc).
     * @param provider
     * @param apiKey
     * @param apiEndpoint
     * @param model
     * @param instructions
     * @param jsonMode
     * @param userMessage
     * @return String
     * */
    private String callProvider(String provider, String apiKey, String apiEndpoint, String model,
        String instructions, boolean jsonMode, String userMessage) throws Exception {
        // OpenAI and Anthropic get their own request/response shape; every other provider name
        // (whatever the user has added under Settings > Lookup, type AI_PROVIDER) is treated as
        // a generic OpenAI-compatible chat-completions endpoint -- covers Azure OpenAI, Groq,
        // self-hosted proxies, etc. without needing a code change per provider.
        if ("OpenAI".equals(provider)) {
            return this.callOpenAi(apiKey, model, instructions, userMessage);
        } else if ("Anthropic".equals(provider)) {
            return this.callAnthropic(apiKey, model, instructions, userMessage);
        } else if ("Ollama".equals(provider)) {
            return this.callOllama(apiEndpoint, model, instructions, jsonMode, userMessage);
        }
        return this.callGenericOpenAiCompatible(provider, apiKey, apiEndpoint, model, instructions, userMessage);
    }

    /** Ollama's native /api/chat -- no auth header, base URL defaults to the local Docker
     * container but can be overridden via apiEndpoint (e.g. a different host/port). */
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
        // keep the model resident between calls -- Ollama's 5-minute default unload made
        // every request after a short pause pay the full reload cost again
        body.addProperty("keep_alive", "30m");
        // constrains token sampling so the response is guaranteed syntactically valid JSON --
        // unlike a "respond with JSON only" instruction, this can't be ignored by the model
        if (jsonMode) {
            body.addProperty("format", "json");
        }
        // Ollama's runtime context window defaults to a small value (often 4096 tokens)
        // regardless of what the underlying model actually supports -- a real document plus
        // this agent's system prompt can easily exceed that and fail with
        // "exceeds the available context size". Request a larger window explicitly.
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

    /** Generic OpenAI-compatible chat-completions call against a user-supplied endpoint --
     * covers self-hosted/proxy setups (e.g. Azure OpenAI, LM Studio, vLLM) that speak the same API shape. */
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

    /**
     * Method use to strip a leading/trailing ```json ... ``` (or plain ``` ... ```) code fence
     * and surrounding whitespace from a jsonMode agent's result -- Ollama's format:"json"
     * constrains sampling to valid JSON, but some model/template combinations (observed with
     * deepseek-r1) still wrap that JSON in a markdown fence, which a downstream JSON.parse()
     * would otherwise choke on.
     * @param text
     * @return String
     * */
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

    /**
     * Method use to validate an agent payload against the allowed providers/file types
     * @param aiAgentDto
     * @return ResponseDto or null when valid
     * */
    private ResponseDto validateAgent(AiAgentDto aiAgentDto) {
        if (isNull(aiAgentDto.getAgentName()) || aiAgentDto.getAgentName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent agentName missing.");
        }
        if (isNull(aiAgentDto.getProvider()) || aiAgentDto.getProvider().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Agent provider missing.");
        }
        // provider names come from Settings > Lookup (type AI_PROVIDER) now, so any non-empty
        // value is accepted here -- OpenAI/Anthropic/Ollama get a bespoke call shape (see
        // callProvider) with a built-in default endpoint, every other name needs an
        // apiEndpoint to reach a generic OpenAI-compatible completions API
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

    /**
     * Method use to copy Dto values onto an entity -- encrypts+stores a new apiKey only when a
     * non-empty one was supplied, leaving the existing (already-encrypted) key untouched otherwise.
     * @param aiAgent
     * @param dto
     * */
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

    /**
     * Method use to map an AiAgent entity to its Dto -- deliberately never sets apiKey
     * @param aiAgent
     * @return AiAgentDto
     * */
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

    /**
     * Method use to lazily backfill toolUuid for agents saved before this field existed --
     * called wherever an agent is read via the normal id-based paths, so every agent ends up
     * with one after being listed/opened once, with no manual migration needed.
     * @param aiAgent
     * @return AiAgent
     * */
    private AiAgent ensureToolUuid(AiAgent aiAgent) {
        if (isNull(aiAgent.getToolUuid()) || aiAgent.getToolUuid().trim().isEmpty()) {
            aiAgent.setToolUuid(UUID.randomUUID().toString());
            aiAgent = this.aiAgentRepository.save(aiAgent);
        }
        return aiAgent;
    }

}
