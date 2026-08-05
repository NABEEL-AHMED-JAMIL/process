package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import process.model.dto.OllamaModelDto;
import process.model.service.OllamaService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service use to manage models on the local Ollama container (list what's already pulled,
 * pull a new one, delete one) -- proxies Ollama's own HTTP API, no auth needed.
 * @author Nabeel Ahmed
 */
@Service
public class OllamaServiceImpl implements OllamaService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${ollama.base.url:http://host.docker.internal:11434}")
    private String baseUrl;

    private final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(30, TimeUnit.SECONDS)
        .build();

    /** Pulling a model downloads several GB over the network -- needs a much longer timeout
     * than any other Ollama call. Kept as a separate client so list/delete stay fast-failing. */
    private final OkHttpClient pullHttpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(30, TimeUnit.MINUTES)
        .build();

    /**
     * Method use to list every model currently pulled into the local Ollama container
     * @return List<OllamaModelDto>
     * */
    @Override
    public Object listModels() throws Exception {
        Request request = new Request.Builder()
            .url(this.baseUrl + "/api/tags")
            .get()
            .build();
        JsonObject response = this.execute(this.httpClient, request);
        List<OllamaModelDto> models = new ArrayList<>();
        JsonArray modelArray = response.has("models") ? response.getAsJsonArray("models") : new JsonArray();
        for (int i = 0; i < modelArray.size(); i++) {
            JsonObject model = modelArray.get(i).getAsJsonObject();
            OllamaModelDto dto = new OllamaModelDto();
            dto.setName(this.stringOrNull(model, "name"));
            dto.setSize(model.has("size") && !model.get("size").isJsonNull() ? model.get("size").getAsLong() : null);
            dto.setModifiedAt(this.stringOrNull(model, "modified_at"));
            dto.setDigest(this.stringOrNull(model, "digest"));
            if (model.has("details") && model.get("details").isJsonObject()) {
                JsonObject details = model.getAsJsonObject("details");
                dto.setFamily(this.stringOrNull(details, "family"));
                dto.setParameterSize(this.stringOrNull(details, "parameter_size"));
                dto.setQuantizationLevel(this.stringOrNull(details, "quantization_level"));
            }
            models.add(dto);
        }
        return models;
    }

    /**
     * Method use to pull (download) a model into the local Ollama container -- blocks until
     * the pull finishes or fails, since we call Ollama with stream:false.
     * @param name
     * @return Object
     * */
    @Override
    public Object pullModel(String name) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("stream", false);
        Request request = new Request.Builder()
            .url(this.baseUrl + "/api/pull")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        return this.execute(this.pullHttpClient, request);
    }

    /**
     * Method use to delete a model from the local Ollama container
     * @param name
     * @return Object
     * */
    @Override
    public Object deleteModel(String name) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        Request request = new Request.Builder()
            .url(this.baseUrl + "/api/delete")
            .delete(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        try (Response response = this.httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                throw new IllegalStateException(String.format("HTTP %d: %s", response.code(), responseBody));
            }
        }
        return name;
    }

    private String stringOrNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private JsonObject execute(OkHttpClient client, Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException(String.format("HTTP %d: %s", response.code(), responseBody));
            }
            return this.gson.fromJson(responseBody, JsonObject.class);
        }
    }

}
