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
import process.correlation.CorrelationInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link FormWorkflows} through workflow-service's internal door (POST /internal/workflows/definition and /start), with
 * the internal token: the submission names its workspace and requester, and its id is the start's event id, so a retried
 * submission starts one request.
 */
@Component
public class HttpFormWorkflows implements FormWorkflows {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String base;
    private final String serviceToken;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();

    public HttpFormWorkflows(@Value("${workflow.url:http://workflow:9190}") String workflowUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this.base = workflowUrl.replaceAll("/+$", "") + "/internal/workflows";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public Optional<Workflow> find(long tenantId, String key) {
        ObjectNode body = this.json.createObjectNode();
        body.put("tenantId", tenantId);
        body.put("key", key);
        JsonNode answer = this.post("/definition", body, true);
        if (answer == null) {
            return Optional.empty();
        }
        JsonNode d = answer.path("data");
        return Optional.of(new Workflow(d.path("key").asText(key), d.path("name").asText(key), d.path("status").asText(""),
            d.path("currentVersion").asInt(0)));
    }

    @Override
    public Started start(long tenantId, String key, long submissionId, String title, Map<String, Object> subject, Long requestedBy) {
        ObjectNode body = this.json.createObjectNode();
        body.put("tenantId", tenantId);
        body.put("definitionKey", key);
        body.put("subjectId", FormWorkflows.subjectOf(submissionId));
        body.put("title", title);
        body.set("subject", this.json.valueToTree(subject));
        if (requestedBy != null) {
            body.put("requestedBy", requestedBy);
        }
        body.put("eventId", "form-submission-" + submissionId);
        JsonNode answer = this.post("/start", body, false);
        return new Started(answer.path("data").path("id").asLong(), answer.path("data").path("state").asText(""));
    }

    /** The answer; null for a 404 when {@code missingIsNull}; an exception for anything else that is not SUCCESS. */
    private JsonNode post(String path, ObjectNode body, boolean missingIsNull) {
        Request call;
        try {
            call = new Request.Builder().url(this.base + path).header("X-Internal-Token", this.serviceToken)
                .post(RequestBody.create(this.json.writeValueAsBytes(body), JSON)).build();
        } catch (IOException unwritable) {
            throw new IllegalStateException("The request to workflow-service could not be written.", unwritable);
        }
        try (Response response = this.http.newCall(call).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
            if (response.code() == 404 && missingIsNull) {
                return null;
            }
            if (response.code() != 200 || answer == null || !"SUCCESS".equals(answer.path("status").asText())) {
                String message = answer == null ? "" : answer.path("message").asText("");
                throw new IllegalStateException(message.isEmpty() ? "workflow-service answered HTTP " + response.code() : message);
            }
            return answer;
        } catch (IOException unreachable) {
            throw new IllegalStateException("workflow-service could not be reached (" + unreachable.getMessage() + ")", unreachable);
        }
    }
}
