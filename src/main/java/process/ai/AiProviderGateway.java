package process.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one place that speaks to a model provider. Every caller -- a prompt's Try it, a
 * pipeline step, the file chat through the agent aliases -- hands it a {@link ChatRequest}
 * and gets back the text with the token usage the provider reported, so cost can be a
 * column rather than a guess. It also lists a provider's models for "Test connection".
 *
 * Providers: OpenAI, Anthropic, Ollama, AzureOpenAI, and anything else is treated as
 * OpenAI-compatible at the given endpoint (LM Studio, vLLM, Groq, ...).
 */
@Component
public class AiProviderGateway {

    public static final String OLLAMA_DEFAULT_BASE_URL = "http://host.docker.internal:11434";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Gson gson = new Gson();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(10, TimeUnit.MINUTES)
        .build();

    /** What to send. `jsonMode` asks providers that support it for JSON; others are validated after. */
    public static class ChatRequest {
        public String provider;
        public String apiKey;
        public String apiEndpoint;
        public String model;
        public String system;
        public String user;
        public boolean jsonMode;
        public Double temperature;
        public Integer maxTokens;
    }

    /** What came back, with usage where the provider reports it (both -1 when it does not). */
    public static class ChatAnswer {
        public final String text;
        public final int tokensIn;
        public final int tokensOut;
        public ChatAnswer(String text, int tokensIn, int tokensOut) {
            this.text = text; this.tokensIn = tokensIn; this.tokensOut = tokensOut;
        }
    }

    public ChatAnswer chat(ChatRequest req) throws Exception {
        String provider = req.provider == null ? "" : req.provider;
        switch (provider) {
            case "OpenAI": return this.openAiStyle("https://api.openai.com/v1/chat/completions", "Authorization", "Bearer " + req.apiKey, req);
            case "Anthropic": return this.anthropic(req);
            case "Ollama": return this.ollama(req);
            case "AzureOpenAI":
                if (blank(req.apiEndpoint)) {
                    throw new IllegalStateException("Azure OpenAI needs the full deployment URL as its API endpoint "
                        + "(https://<resource>.openai.azure.com/openai/deployments/<deployment>/chat/completions?api-version=...).");
                }
                return this.openAiStyle(req.apiEndpoint.trim(), "api-key", req.apiKey, req);
            default:
                if (blank(req.apiEndpoint)) {
                    throw new IllegalStateException("Provider \"" + provider + "\" has no apiEndpoint configured.");
                }
                return this.openAiStyle(req.apiEndpoint.trim(), "Authorization", "Bearer " + req.apiKey, req);
        }
    }

    /**
     * The models a connection can run, as the provider lists them. Ollama and OpenAI-style
     * providers answer a list; Anthropic does too; a provider with no such call gets an
     * empty list and the connection keeps whatever models were typed.
     */
    public List<String> listModels(String provider, String apiKey, String apiEndpoint) throws Exception {
        List<String> models = new ArrayList<>();
        provider = provider == null ? "" : provider;
        if ("Ollama".equals(provider)) {
            String base = blank(apiEndpoint) ? OLLAMA_DEFAULT_BASE_URL : apiEndpoint.trim().replaceAll("/+$", "");
            JsonObject answer = this.execute(new Request.Builder().url(base + "/api/tags").get().build());
            for (JsonElement m : answer.getAsJsonArray("models")) models.add(m.getAsJsonObject().get("name").getAsString());
            return models;
        }
        if ("Anthropic".equals(provider)) {
            JsonObject answer = this.execute(new Request.Builder().url("https://api.anthropic.com/v1/models")
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").get().build());
            for (JsonElement m : answer.getAsJsonArray("data")) models.add(m.getAsJsonObject().get("id").getAsString());
            return models;
        }
        if ("AzureOpenAI".equals(provider)) {
            // Azure's deployment URL is one deployment; there is no list to fetch from it.
            return models;
        }
        String url = "OpenAI".equals(provider) ? "https://api.openai.com/v1/models"
            : (blank(apiEndpoint) ? null : apiEndpoint.trim().replaceAll("/chat/completions/?$", "").replaceAll("/+$", "") + "/models");
        if (url == null) return models;
        JsonObject answer = this.execute(new Request.Builder().url(url).header("Authorization", "Bearer " + apiKey).get().build());
        if (answer.has("data")) {
            for (JsonElement m : answer.getAsJsonArray("data")) models.add(m.getAsJsonObject().get("id").getAsString());
        }
        return models;
    }

    private ChatAnswer openAiStyle(String url, String authHeader, String authValue, ChatRequest req) throws Exception {
        JsonArray messages = new JsonArray();
        if (!blank(req.system)) messages.add(this.message("system", req.system));
        messages.add(this.message("user", req.user));
        JsonObject body = new JsonObject();
        body.addProperty("model", req.model);
        body.add("messages", messages);
        if (req.temperature != null) body.addProperty("temperature", req.temperature);
        if (req.maxTokens != null) body.addProperty("max_tokens", req.maxTokens);
        if (req.jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        JsonObject answer = this.execute(new Request.Builder().url(url).header(authHeader, authValue)
            .post(RequestBody.create(this.gson.toJson(body), JSON)).build());
        String text = answer.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        JsonObject usage = answer.has("usage") && answer.get("usage").isJsonObject() ? answer.getAsJsonObject("usage") : null;
        return new ChatAnswer(text, intOf(usage, "prompt_tokens"), intOf(usage, "completion_tokens"));
    }

    private ChatAnswer anthropic(ChatRequest req) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(this.message("user", req.user));
        JsonObject body = new JsonObject();
        body.addProperty("model", req.model);
        if (!blank(req.system)) body.addProperty("system", req.system);
        body.addProperty("max_tokens", req.maxTokens != null ? req.maxTokens : 4096);
        if (req.temperature != null) body.addProperty("temperature", req.temperature);
        body.add("messages", messages);
        JsonObject answer = this.execute(new Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", req.apiKey).header("anthropic-version", "2023-06-01")
            .post(RequestBody.create(this.gson.toJson(body), JSON)).build());
        String text = answer.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        JsonObject usage = answer.has("usage") ? answer.getAsJsonObject("usage") : null;
        return new ChatAnswer(text, intOf(usage, "input_tokens"), intOf(usage, "output_tokens"));
    }

    private ChatAnswer ollama(ChatRequest req) throws Exception {
        String base = blank(req.apiEndpoint) ? OLLAMA_DEFAULT_BASE_URL : req.apiEndpoint.trim().replaceAll("/+$", "");
        JsonArray messages = new JsonArray();
        if (!blank(req.system)) messages.add(this.message("system", req.system));
        messages.add(this.message("user", req.user));
        JsonObject body = new JsonObject();
        body.addProperty("model", req.model);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("keep_alive", "30m");
        if (req.jsonMode) body.addProperty("format", "json");
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", 16384);
        if (req.temperature != null) options.addProperty("temperature", req.temperature);
        if (req.maxTokens != null) options.addProperty("num_predict", req.maxTokens);
        body.add("options", options);
        JsonObject answer = this.execute(new Request.Builder().url(base + "/api/chat")
            .post(RequestBody.create(this.gson.toJson(body), JSON)).build());
        String text = answer.getAsJsonObject("message").get("content").getAsString();
        return new ChatAnswer(text, intOf(answer, "prompt_eval_count"), intOf(answer, "eval_count"));
    }

    private static int intOf(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : -1;
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    private JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private JsonObject execute(Request request) throws Exception {
        try (Response response = this.httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ProviderException(response.code(), String.format("HTTP %d: %s", response.code(), body));
            }
            return this.gson.fromJson(body, JsonObject.class);
        }
    }

    /** A provider's refusal, with its status so a caller can tell a 429 from a 401. */
    public static class ProviderException extends Exception {
        public final int status;
        public ProviderException(int status, String message) { super(message); this.status = status; }
    }
}
