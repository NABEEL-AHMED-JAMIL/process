package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ItemReviews} through workflow-service's internal door (POST /api/v1/internal/workflows/ensure, then /start) with the
 * internal token, as Forms start a submission's approval (HttpFormWorkflows): the built-in workflow is made the first time a
 * workspace needs it, then the request is started with the caller's event id as its command key.
 */
@Component
public class HttpItemReviews implements ItemReviews {

    private static final MediaType JSON_TYPE = MediaType.get("application/json");

    private final String base;
    private final String serviceToken;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();

    public HttpItemReviews(@Value("${workflow.url:http://workflow:9190}") String workflowUrl, @Value("${internal.service-token:}") String serviceToken) {
        this.base = workflowUrl.replaceAll("/+$", "") + "/api/v1/internal/workflows";
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public Optional<String> unavailable() {
        return this.serviceToken.isEmpty() ? Optional.of("Task inbox tasks cannot be made here: no internal service token is set.")
            : Optional.empty();
    }

    @Override
    public Started send(ItemReviews.ReviewRequest r) {
        ObjectNode ensure = this.json.createObjectNode();
        ensure.put("tenantId", r.tenantId);
        ensure.put("key", DEFINITION_KEY);
        ensure.put("name", "Pipeline items review");
        ensure.put("description", "Items of a pipeline run someone asked a person to check -- picked on the run's Items tab, or pages an OCR step "
            + "was not sure enough of: the reviewers named check them on the run's Items tab and mark the task done.");
        ensure.put("subjectType", "pipeline_items");
        ensure.set("steps", steps());
        ensure.put("service", "the pipeline engine (item reviews)");   // ZT-S10: plain words, no build ticket
        JsonNode ready = this.post("/ensure", ensure).path("data");
        if (!"Active".equals(ready.path("status").asText("")) || ready.path("currentVersion").asInt(0) == 0) {
            throw new IllegalStateException("The workspace's \"Pipeline items review\" workflow is switched off, so no Task inbox task was made.");
        }
        ObjectNode start = this.json.createObjectNode();
        start.put("tenantId", r.tenantId);
        start.put("definitionKey", DEFINITION_KEY);
        start.put("subjectId", r.subjectId);
        start.put("title", r.title == null || r.title.length() <= 255 ? r.title : r.title.substring(0, 255));
        ObjectNode subject = this.json.valueToTree(r.subject);
        ObjectNode reviewers = subject.putObject("reviewers");
        reviewers.put("kind", r.reviewersKind);
        reviewers.put("value", r.reviewersValue);
        start.set("subject", subject);
        start.put("eventId", r.eventId);
        JsonNode data = this.post("/start", start).path("data");
        return new Started(data.path("id").asLong(), data.path("state").asText(""));
    }

    /** The built-in workflow's one step: the reviewers the subject names check the items and mark it done (or say why not). */
    static JsonNode steps() {
        ObjectMapper json = new ObjectMapper();
        ObjectNode review = json.createObjectNode();
        review.put("key", "check");
        review.put("type", "approval");
        review.put("name", "Check the items");
        ObjectNode assignee = review.putObject("assignee");
        assignee.put("kind", "subject");
        assignee.put("value", "reviewers");
        review.put("rejectNeedsComment", true);
        review.put("onReject", "end");
        ObjectNode steps = json.createObjectNode();
        ArrayNode list = steps.putArray("steps");
        list.add(review);
        return steps;
    }

    private JsonNode post(String path, ObjectNode body) {
        Request call;
        try {
            call = new Request.Builder().url(this.base + path).header("X-Internal-Token", this.serviceToken)
                .post(RequestBody.create(this.json.writeValueAsBytes(body), JSON_TYPE)).build();
        } catch (IOException unwritable) {
            throw new IllegalStateException("The request to workflow-service could not be written.", unwritable);
        }
        try (Response response = this.http.newCall(call).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
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
