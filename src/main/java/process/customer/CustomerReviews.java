package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.enums.ReviewParty;
import process.pipeline.review.RunReviewRequest;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.SUCCESS;

/**
 * The customer's review of a run's results through the API (MIG-334, absorbing MIG-237's customer half; OpenAPI getReview
 * and decideReview).
 *
 * <ul>
 *   <li>GET /v1/runs/{id}/review: who must decide and what each decided; scope runs:read.</li>
 *   <li>POST /v1/runs/{id}/review {decision, reason, comment, rerun}: the customer's decision, as the CUSTOMER party (an
 *   API client is that party, ReviewParties); scope reviews:write; Idempotency-Key required.</li>
 * </ul>
 * The decision goes through {@link RunReviewService#decide} -- the one way any decision is recorded, the console's
 * included -- so the console's rules are the API's: a completed run, a pipeline that asks for a customer review, results
 * still pending, one decision per party, a rejection says why. What the request itself gets wrong is a 422 with the
 * field's path; what the run's state refuses is a 409; another workspace's run is a 404.
 */
@Service
public class CustomerReviews {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CustomerRuns runs;
    private final RunReviews reviews;
    private final RunReviewService service;
    private final Idempotency idempotency;

    public CustomerReviews(CustomerRuns runs, RunReviews reviews, RunReviewService service, Idempotency idempotency) {
        this.runs = runs;
        this.reviews = reviews;
        this.service = service;
        this.idempotency = idempotency;
    }

    @Transactional(readOnly = true)
    public CustomerAnswer get(String runId) {
        String instance = "/v1/runs/" + runId + "/review";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Optional<CustomerRuns.Found> found = this.runs.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, CustomerRuns.NO_SUCH_RUN), instance);
        }
        return CustomerAnswer.of(200, CustomerViews.review(this.reviews.summary(found.get().run, found.get().job)), null);
    }

    /** POST /v1/runs/{id}/review with the raw body, so "the same request" is exactly what was sent. */
    public CustomerAnswer decide(String runId, byte[] body, String idempotencyKey) {
        String instance = "/v1/runs/" + runId + "/review";
        if (!TenantContext.hasScope(ApiScopes.REVIEWS_WRITE)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.REVIEWS_WRITE), instance);
        }
        byte[] sent = body == null ? new byte[0] : body;
        if (sent.length > CustomerPipelines.MAX_BODY_BYTES) {
            return CustomerAnswer.problem(Problem.of(413, "A review is at most 1 MB of JSON."), instance);
        }
        String fingerprint = IdempotencyKeys.fingerprint("POST".getBytes(StandardCharsets.UTF_8), instance.getBytes(StandardCharsets.UTF_8), sent);
        return this.idempotency.once(idempotencyKey, instance, fingerprint, () -> this.record(runId, sent, instance));
    }

    private CustomerAnswer record(String runId, byte[] body, String instance) {
        JsonNode request;
        try {
            request = body.length == 0 ? null : JSON.readTree(body);
        } catch (IOException unreadable) {
            return CustomerAnswer.problem(Problem.of(400, "The request is not JSON."), instance);
        }
        if (request == null || !request.isObject()) {
            return CustomerAnswer.problem(Problem.of(400, "The request is a JSON object: {decision, reason, comment, rerun}."), instance);
        }
        List<Problem.FieldError> errors = new ArrayList<>();
        String decision = text(request, "decision", errors);
        String reason = text(request, "reason", errors);
        String comment = text(request, "comment", errors);
        JsonNode rerunNode = request.get("rerun");
        boolean rerun = false;
        if (rerunNode != null && !rerunNode.isNull()) {
            if (!rerunNode.isBoolean()) {
                errors.add(new Problem.FieldError("rerun", "must be true or false"));
            } else {
                rerun = rerunNode.asBoolean();
            }
        }
        String word = decision == null ? null : decision.trim().toLowerCase(Locale.ROOT);
        if (word == null || word.isEmpty()) {
            errors.add(new Problem.FieldError("decision", "is required: approved or rejected"));
        } else if (!"approved".equals(word) && !"rejected".equals(word)) {
            errors.add(new Problem.FieldError("decision", "must be approved or rejected"));
        }
        if ("rejected".equals(word) && (reason == null || reason.trim().isEmpty())) {
            errors.add(new Problem.FieldError("reason", "a rejection says why"));
        }
        if (rerun && !"rejected".equals(word)) {
            errors.add(new Problem.FieldError("rerun", "goes with a rejection only"));
        }
        if (reason != null && reason.length() > 2000) {
            errors.add(new Problem.FieldError("reason", "at most 2000 characters"));
        }
        if (comment != null && comment.length() > 2000) {
            errors.add(new Problem.FieldError("comment", "at most 2000 characters"));
        }
        if (!errors.isEmpty()) {
            return CustomerAnswer.problem(Problem.validation("The review does not say what it decides.", errors), instance);
        }
        Optional<CustomerRuns.Found> found = this.runs.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, CustomerRuns.NO_SUCH_RUN), instance);
        }
        RunReviewRequest decided = new RunReviewRequest();
        decided.setJobQueueId(found.get().row.runId);
        decided.setDecision(word.toUpperCase(Locale.ROOT));
        decided.setReason(reason);
        decided.setComment(comment);
        decided.setRerun(rerun);
        ResponseDto answer = this.service.decide(decided, ReviewParty.CUSTOMER);
        if (!SUCCESS.equals(answer.getStatus())) {
            // What is left once the request and the run are good is the run's state: not completed, no customer review
            // asked for, already decided -- the console's own words.
            return CustomerAnswer.problem(Problem.of(409, answer.getMessage()).kind("review-not-open"), instance);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) answer.getData();
        Map<String, Object> review = CustomerViews.review(data);
        Object again = data.get("rerun");
        if (again instanceof Map) {
            Map<?, ?> outcome = (Map<?, ?>) again;
            Map<String, Object> rerunView = new LinkedHashMap<>();
            rerunView.put("queued", Boolean.TRUE.equals(outcome.get("queued")));
            rerunView.put("runId", outcome.get("jobQueueId") == null ? null : String.valueOf(outcome.get("jobQueueId")));
            rerunView.put("message", outcome.get("message") == null ? null : String.valueOf(outcome.get("message")));
            review.put("rerun", rerunView);
        }
        return CustomerAnswer.of(200, review, null);
    }

    private static String text(JsonNode request, String field, List<Problem.FieldError> errors) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(new Problem.FieldError(field, "must be text"));
            return null;
        }
        return value.asText();
    }
}
