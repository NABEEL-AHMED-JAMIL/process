package process.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.AiPromptDto;
import process.model.dto.ResponseDto;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * AiPort as calls to ai-service (ADR-020), the only way Core reaches prompts, model connections and
 * prompt runs.
 *
 * Every call carries the internal token. A server step runs on the scheduler's thread, outside any
 * request, so it carries only that: AI takes the tenant from the call, which Core read from the job.
 * The agent calls run inside the signed-in user's request (file chat, the job assistant) and carry
 * that user's own bearer token too, so AI checks the agent belongs to them.
 */
@Component
public class HttpAi implements AiPort {

    private static final Logger logger = LoggerFactory.getLogger(HttpAi.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final String base;
    private final String serviceToken;
    private final ObjectMapper json = new ObjectMapper();
    // A step is a model call: its provider may take minutes, and the AI service retries 429 and 5xx.
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build();

    @Autowired
    public HttpAi(@Value("${ai.url:http://ai:9150}") String aiUrl, @Value("${internal.service-token:}") String serviceToken) {
        this.base = aiUrl.replaceAll("/+$", "") + "/api/v1/internal/ai";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public StepResult runStep(Long tenantId, Long jobQueueId, String stepTag, Long promptId, Map<String, String> values) {
        ObjectNode body = this.json.createObjectNode();
        body.put("tenantId", tenantId);
        body.put("jobQueueId", jobQueueId);
        body.put("stepTag", stepTag);
        body.put("promptId", promptId);
        body.set("values", this.json.valueToTree(values));
        try (Response response = this.send("/steps/run", body, false)) {
            JsonNode answer = this.readJson(response);
            if (response.code() != 200 || answer == null) {
                return StepResult.failed("The AI service refused the step: " + this.messageOf(answer, response));
            }
            JsonNode data = answer.has("data") && answer.get("data").isObject() ? answer.get("data") : answer;
            StepResult result = new StepResult();
            result.status = text(data, "status");
            result.output = text(data, "output");
            result.error = text(data, "error");
            result.promptName = text(data, "promptName");
            result.promptVersion = integer(data, "promptVersion");
            result.latencyMs = integer(data, "latencyMs");
            result.tokensIn = integer(data, "tokensIn");
            result.tokensOut = integer(data, "tokensOut");
            result.reused = data.path("reused").asBoolean(false);
            return result;
        } catch (IOException unreachable) {
            logger.warn("AI step <{}> for run {}: {}", stepTag, jobQueueId, unreachable.getMessage());
            return StepResult.failed("The AI service could not be reached, so the step did not run.");
        }
    }

    @Override
    public Map<Long, PromptInfo> prompts(Collection<Long> promptIds) throws AiUnavailableException {
        Map<Long, PromptInfo> found = new LinkedHashMap<>();
        if (promptIds == null || promptIds.stream().noneMatch(Objects::nonNull)) {
            return found;
        }
        ObjectNode body = this.json.createObjectNode();
        ArrayNode ids = body.putArray("ids");
        promptIds.stream().filter(Objects::nonNull).distinct().forEach(ids::add);
        try (Response response = this.send("/prompts/resolve", body, false)) {
            JsonNode answer = this.readJson(response);
            if (response.code() != 200 || answer == null) {
                throw new AiUnavailableException("The AI service could not say which prompts these are: "
                    + this.messageOf(answer, response), null);
            }
            JsonNode rows = answer.isArray() ? answer : answer.path("data");
            for (JsonNode row : rows) {
                PromptInfo prompt = new PromptInfo();
                prompt.promptId = row.path("promptId").asLong();
                prompt.promptUuid = text(row, "promptUuid");
                prompt.name = text(row, "name");
                prompt.version = integer(row, "version");
                prompt.status = text(row, "status");
                prompt.tenantId = row.hasNonNull("tenantId") ? row.get("tenantId").asLong() : null;
                JsonNode variables = row.path("variables");
                if (variables.isTextual()) {
                    variables = this.json.readTree(variables.asText());
                }
                if (variables != null && variables.isArray()) {
                    for (JsonNode v : variables) {
                        prompt.variables.add(this.json.treeToValue(v, AiPromptDto.Variable.class));
                    }
                }
                found.put(prompt.promptId, prompt);
            }
            return found;
        } catch (IOException unreachable) {
            throw new AiUnavailableException("The AI service could not be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    @Override
    public ResponseDto runtimeConfig(Long aiAgentId) throws AiUnavailableException {
        ObjectNode body = this.json.createObjectNode();
        body.put("aiAgentId", aiAgentId);
        ResponseDto answer = this.answerOf("/agents/runtimeConfig", body);
        if (SUCCESS.equals(answer.getStatus()) && answer.getData() != null) {
            answer.setData(this.json.convertValue(answer.getData(), AiAgentRuntimeConfigDto.class));
        }
        return answer;
    }

    @Override
    public ResponseDto adHoc(Long aiAgentId, String instructions, String text, Boolean jsonMode) throws AiUnavailableException {
        ObjectNode body = this.json.createObjectNode();
        body.put("aiAgentId", aiAgentId);
        body.put("instructions", instructions);
        body.put("text", text);
        if (jsonMode != null) {
            body.put("jsonMode", jsonMode);
        }
        return this.answerOf("/adhoc", body);
    }

    /** A user-facing answer relayed as it came: the AI service's own status, message and data. */
    private ResponseDto answerOf(String path, ObjectNode body) throws AiUnavailableException {
        try (Response response = this.send(path, body, true)) {
            JsonNode answer = this.readJson(response);
            if (answer == null || !answer.has("status")) {
                return new ResponseDto(ERROR, "The AI service answered " + response.code() + ".");
            }
            Object data = answer.hasNonNull("data") ? this.json.treeToValue(answer.get("data"), Object.class) : null;
            return new ResponseDto(answer.get("status").asText(), text(answer, "message"), data);
        } catch (IOException unreachable) {
            throw new AiUnavailableException("The AI service could not be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    private Response send(String path, ObjectNode body, boolean asCaller) throws IOException {
        Request.Builder request = new Request.Builder().url(this.base + path)
            .header("X-Internal-Token", this.serviceToken)
            .post(RequestBody.create(this.json.writeValueAsBytes(body), JSON));
        if (asCaller) {
            request.header("Authorization", callerAuthorization());
        }
        return this.http.newCall(request.build()).execute();
    }

    /** The signed-in user's own token, from the request this call is part of. */
    private static String callerAuthorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String authorization = attributes instanceof ServletRequestAttributes
            ? ((ServletRequestAttributes) attributes).getRequest().getHeader("Authorization") : null;
        if (authorization == null || authorization.trim().isEmpty()) {
            throw new IllegalStateException("No signed-in caller to reach the AI service as.");
        }
        return authorization;
    }

    private JsonNode readJson(Response response) {
        try {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            return text.isEmpty() ? null : this.json.readTree(text);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private String messageOf(JsonNode answer, Response response) {
        return answer != null && answer.hasNonNull("message") ? answer.get("message").asText()
            : "it answered " + response.code() + ".";
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asInt() : null;
    }
}
