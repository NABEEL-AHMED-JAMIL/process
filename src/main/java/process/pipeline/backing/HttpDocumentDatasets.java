package process.pipeline.backing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link DocumentDatasets} over ai-service's /internal/ai/documents/dataset, with the internal service token and the
 * workspace in the body (the step engine's thread has nobody signed in; ai-service reads as that workspace only). One
 * page per call; the response is read as a stream into the page, so a call holds one page.
 */
@Component
public class HttpDocumentDatasets implements DocumentDatasets {

    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final String url;
    private final String serviceToken;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build();

    public HttpDocumentDatasets(@Value("${ai.url:http://ai:9150}") String aiUrl, @Value("${internal.service-token:}") String serviceToken) {
        this.url = (aiUrl == null ? "" : aiUrl.trim().replaceAll("/+$", "")) + "/api/v1/internal/ai/documents/dataset";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public Optional<String> unavailable() {
        return this.serviceToken.isEmpty() ? Optional.of("This Core has no internal service token, so it cannot read Document Intelligence.")
            : Optional.empty();
    }

    @Override
    public Page read(DocumentDatasets.Request r) throws IOException {
        ObjectNode body = this.json.createObjectNode();
        body.put("tenantId", r.tenantId);
        body.put("documentTypeId", r.documentTypeId);
        body.put("status", r.status == null ? APPROVED : r.status);
        body.put("after", Math.max(0, r.after));
        if (r.from != null) {
            body.put("from", r.from);
        }
        if (r.to != null) {
            body.put("to", r.to);
        }
        if (r.extractionIds != null && !r.extractionIds.isEmpty()) {
            ArrayNode ids = body.putArray("extractionIds");
            r.extractionIds.forEach(ids::add);
        }
        body.put("limit", Math.max(1, Math.min(r.limit, MAX_PAGE)));
        body.put("names", r.names);
        if (r.jobQueueId != null) {
            body.put("jobQueueId", r.jobQueueId);
        }
        if (r.readKey != null) {
            body.put("readKey", r.readKey);
        }
        okhttp3.Request request = new okhttp3.Request.Builder().url(this.url).header("X-Internal-Token", this.serviceToken)
            .post(RequestBody.create(this.json.writeValueAsBytes(body), JSON_TYPE)).build();
        try (Response response = this.http.newCall(request).execute()) {
            ResponseBody content = response.body();
            JsonNode answer;
            try (InputStream in = content == null ? null : content.byteStream()) {
                answer = in == null ? null : this.json.readTree(in);
            } catch (IOException unreadable) {
                answer = null;
            }
            if (response.code() != 200 || answer == null || !answer.path("data").isObject()) {
                String why = answer != null && answer.hasNonNull("message") ? answer.get("message").asText() : "it answered " + response.code() + ".";
                if (response.code() == 404 || response.code() == 400) {
                    throw new IllegalArgumentException(why);
                }
                throw new IOException("Document Intelligence did not answer the read: " + why);
            }
            return pageOf(answer.get("data"));
        }
    }

    static Page pageOf(JsonNode data) {
        Page page = new Page();
        page.documentTypeId = data.path("documentTypeId").asLong();
        page.typeKey = data.path("typeKey").asText(null);
        page.name = data.path("name").asText(null);
        data.path("fields").forEach(f -> page.fields.add(f.asText()));
        data.path("tables").forEach(t -> page.tables.add(t.asText()));
        Iterator<Map.Entry<String, JsonNode>> columns = data.path("tableColumns").fields();
        while (columns.hasNext()) {
            Map.Entry<String, JsonNode> table = columns.next();
            List<String> names = new ArrayList<>();
            table.getValue().forEach(c -> names.add(c.asText()));
            page.tableColumns.put(table.getKey(), names);
        }
        data.path("rows").forEach(page.rows::add);
        page.next = data.hasNonNull("next") ? data.get("next").asLong() : null;
        return page;
    }
}
