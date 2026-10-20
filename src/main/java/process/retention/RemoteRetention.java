package process.retention;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MIG-354: the classes other services own, removed by those services -- Core only asks. One internal endpoint per
 * service, the service token in X-Internal-Token, the workspace in the body:
 *
 * <pre>
 * POST {ai}/api/v1/internal/ai/retention                       AI_CONVERSATIONS, DOCUMENTS
 * POST {storage}/api/v1/internal/storage/retention             FILES
 * POST {notifications}/api/v1/internal/notifications/retention NOTIFICATIONS
 * POST {media}/api/v1/internal/media/retention                 REPORTS (Report Studio phase 7)
 *   {"action": "preview" | "purge", "tenantId": 2960, "dataClass": "FILES", "cutoff": "2025-10-13T07:00:00Z",
 *    "limit": 500, "keepRunIds": [..]}
 *   preview -> {"count", "bytes" (or null), "oldest" (an instant or null), "total"}
 *   purge   -> {"deleted", "bytes", "more"}
 * POST {storage}/api/v1/internal/storage/retention/objects     a form submission's objects in the workspace's inbox
 *   {"tenantId", "objects": [{"alias", "key"}]} -> {"deleted", "bytes"}
 * </pre>
 *
 * A service each answers as the workspace (row security) and removes only what is older than the cutoff. A 404 is a
 * service deployed without retention ({@link RetentionTarget.Unavailable}: the class is reported, nothing removed); any
 * other refusal, or no answer, is a failure of the class this pass.
 */
@Component
public class RemoteRetention {

    private static final MediaType JSON = MediaType.get("application/json");

    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http;
    private final String ai;
    private final String storage;
    private final String notifications;
    private final String media;
    private final String serviceToken;

    @Autowired
    public RemoteRetention(@Value("${ai.url:http://ai:9150}") String aiUrl, @Value("${storage.service-url:http://storage:9120}") String storageUrl,
        @Value("${notifications.url:http://notifications:9100}") String notificationsUrl, @Value("${media.url:http://media:9110}") String mediaUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this(new OkHttpClient.Builder().addInterceptor(new CorrelationInterceptor()).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES).writeTimeout(30, TimeUnit.SECONDS).build(), aiUrl, storageUrl, notificationsUrl, mediaUrl,
            serviceToken);
    }

    RemoteRetention(OkHttpClient http, String aiUrl, String storageUrl, String notificationsUrl, String mediaUrl, String serviceToken) {
        this.http = http;
        this.ai = api(aiUrl) + "/internal/ai/retention";
        this.storage = api(storageUrl) + "/internal/storage/retention";
        this.notifications = api(notificationsUrl) + "/internal/notifications/retention";
        this.media = api(mediaUrl) + "/internal/media/retention";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    private static String api(String base) {
        return base.replaceAll("/+$", "") + "/api/v1";
    }

    /** The class's data as its owning service holds it. */
    public RetentionTarget target(DataClass dataClass) {
        String url = this.urlOf(dataClass);
        return new RetentionTarget() {
            @Override
            public Tally preview(long tenantId, Instant cutoff, Holds holds) throws Unavailable {
                JsonNode answer = RemoteRetention.this.post(url, RemoteRetention.this.body("preview", tenantId, dataClass, cutoff, 0, holds));
                return Tally.of(answer.path("count").asLong(0), answer.hasNonNull("bytes") ? answer.path("bytes").asLong() : null,
                    instant(answer.path("oldest")), answer.path("total").asLong(0));
            }

            @Override
            public Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) throws Unavailable {
                ObjectNode body = RemoteRetention.this.body("purge", tenantId, dataClass, cutoff, limit, holds);
                body.put("passId", passId);
                JsonNode answer = RemoteRetention.this.post(url, body);
                return Batch.of(answer.path("deleted").asLong(0), answer.path("bytes").asLong(0), answer.path("more").asBoolean(false));
            }
        };
    }

    /**
     * Removes a form submission's objects -- its JSON copy and the files sent with it -- from the workspace's own inbox
     * connection. Storage removes only keys under intake/forms/ and meters each delete as the workspace's churn.
     */
    public RetentionTarget.Batch deleteFormObjects(long tenantId, List<String[]> aliasAndKey) throws RetentionTarget.Unavailable {
        if (aliasAndKey.isEmpty()) {
            return RetentionTarget.Batch.of(0, 0, false);
        }
        ObjectNode body = this.json.createObjectNode();
        body.put("tenantId", tenantId);
        body.put("reason", "retention: form submissions");
        ArrayNode objects = body.putArray("objects");
        for (String[] object : aliasAndKey) {
            objects.addObject().put("alias", object[0]).put("key", object[1]);
        }
        JsonNode answer = this.post(this.storage + "/objects", body);
        return RetentionTarget.Batch.of(answer.path("deleted").asLong(0), answer.path("bytes").asLong(0), false);
    }

    String urlOf(DataClass dataClass) {
        switch (dataClass.owner()) {
            case AI:
                return this.ai;
            case STORAGE:
                return this.storage;
            case NOTIFICATIONS:
                return this.notifications;
            case MEDIA:
                return this.media;
            default:
                throw new IllegalArgumentException(dataClass + " is not another service's.");
        }
    }

    ObjectNode body(String action, long tenantId, DataClass dataClass, Instant cutoff, int limit, Holds holds) {
        ObjectNode body = this.json.createObjectNode();
        body.put("action", action);
        body.put("tenantId", tenantId);
        body.put("dataClass", dataClass.name());
        body.put("cutoff", cutoff.toString());
        if (limit > 0) {
            body.put("limit", limit);
        }
        if (dataClass.knowsRuns() && holds != null) {
            ArrayNode keep = body.putArray("keepRunIds");
            holds.runIds.forEach(keep::add);
        }
        return body;
    }

    private JsonNode post(String url, ObjectNode body) throws RetentionTarget.Unavailable {
        Request request = new Request.Builder().url(url).header("X-Internal-Token", this.serviceToken)
            .post(RequestBody.create(body.toString(), JSON)).build();
        try (Response response = this.http.newCall(request).execute()) {
            ResponseBody content = response.body();
            String text = content == null ? "" : content.string();
            if (response.code() == 404) {
                throw new RetentionTarget.Unavailable("The service that holds it does not offer retention yet.");
            }
            JsonNode answer = text.isEmpty() ? this.json.createObjectNode() : this.json.readTree(text);
            if (answer.has("data") && answer.get("data").isObject()) {
                answer = answer.get("data");
            }
            if (response.code() != 200) {
                String message = answer.path("message").asText("");
                throw new IllegalStateException("The service refused (" + response.code() + ")" + (message.isEmpty() ? "." : ": " + message));
            }
            return answer;
        } catch (IOException unreachable) {
            throw new IllegalStateException("The service could not be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    static Instant instant(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.asText("").isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
