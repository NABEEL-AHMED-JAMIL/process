package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.correlation.CorrelationInterceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * {@link FormDatasets.Registry} as analytics-service's POST /analyticsDataset.json/registerDataset, with the signed-in
 * administrator's own token: Analytics checks the connection is theirs, exactly as when they register one by hand.
 */
@Component
public class HttpAnalyticsDatasets implements FormDatasets.Registry {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String url;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();

    public HttpAnalyticsDatasets(@Value("${analytics.url:http://analytics:9140}") String analyticsUrl) {
        this.url = analyticsUrl.replaceAll("/+$", "") + "/api/v1/analyticsDataset.json/registerDataset";
    }

    @Override
    public long register(String connectionAlias, String path, String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        String authorization = attributes instanceof ServletRequestAttributes
            ? ((ServletRequestAttributes) attributes).getRequest().getHeader("Authorization") : null;
        if (authorization == null || authorization.trim().isEmpty()) {
            throw new IllegalStateException("No signed-in caller to register the dataset as.");
        }
        ObjectNode body = this.json.createObjectNode();
        body.put("datasetName", name);
        body.put("connectionAlias", connectionAlias);
        body.put("datasetPath", path);
        try {
            Request call = new Request.Builder().url(this.url).header("Authorization", authorization)
                .post(RequestBody.create(this.json.writeValueAsBytes(body), JSON)).build();
            try (Response response = this.http.newCall(call).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
                if (response.code() != 200 || answer == null || !"SUCCESS".equals(answer.path("status").asText())) {
                    String message = answer == null ? "" : answer.path("message").asText("");
                    throw new IllegalStateException(message.isEmpty() ? "Analytics answered HTTP " + response.code() : message);
                }
                return answer.path("data").path("analyticsDatasetId").asLong();
            }
        } catch (IOException unreachable) {
            throw new IllegalStateException("Analytics could not be reached (" + unreachable.getMessage() + ")", unreachable);
        }
    }
}
