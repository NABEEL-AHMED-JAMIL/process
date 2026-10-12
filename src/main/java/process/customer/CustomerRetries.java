package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.springframework.stereotype.Service;
import process.pipeline.RunRetries;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * MIG-345: POST /v1/runs/{runId}/retry (ADR-025's planned retry, served). Scope runs:write; Idempotency-Key required, as
 * every creating POST. Body optional: {"from": "failed_step"} (the default) resumes the run from the step that failed,
 * reusing the kept outputs of the steps before it; {"from": "start"} runs every step again. Either is a new attempt of the
 * same run (RunRetries, the console's rules): 202 with the Run (status queued, its new attempt, retry) and Location
 * /v1/runs/{runId}; 409 problem+json when the run is not retryable, its type the reason (/problems/run-not-failed,
 * run-in-flight, pipeline-inactive, workspace-paused, pipeline-changed, input-changed, outputs-expired, not-resumable) and
 * its detail the reason in words. A run that is not the workspace's is 404, as every run read.
 */
@Service
public class CustomerRetries {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String FAILED_STEP = "failed_step";
    static final String START = "start";

    private final CustomerRuns runs;
    private final CustomerRunStore store;
    private final RunRetries retries;
    private final Idempotency idempotency;

    public CustomerRetries(CustomerRuns runs, CustomerRunStore store, RunRetries retries, Idempotency idempotency) {
        this.runs = runs;
        this.store = store;
        this.retries = retries;
        this.idempotency = idempotency;
    }

    public CustomerAnswer retry(String runId, byte[] body, String idempotencyKey) {
        String instance = "/v1/runs/" + runId + "/retry";
        if (!TenantContext.hasScope(ApiScopes.RUNS_WRITE)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_WRITE), instance);
        }
        byte[] sent = body == null ? new byte[0] : body;
        if (sent.length > CustomerPipelines.MAX_BODY_BYTES) {
            return CustomerAnswer.problem(Problem.of(413, "A retry is at most 1 MB of JSON."), instance);
        }
        String fingerprint = IdempotencyKeys.fingerprint("POST".getBytes(StandardCharsets.UTF_8), instance.getBytes(StandardCharsets.UTF_8), sent);
        return this.idempotency.once(idempotencyKey, instance, fingerprint, () -> this.retried(runId, sent, instance));
    }

    private CustomerAnswer retried(String runId, byte[] body, String instance) {
        String from = FAILED_STEP;
        if (body.length > 0 && !new String(body, StandardCharsets.UTF_8).trim().isEmpty()) {
            JsonNode request;
            try {
                request = JSON.readTree(body);
            } catch (IOException unreadable) {
                return CustomerAnswer.problem(Problem.of(400, "The request is not JSON."), instance);
            }
            if (request == null || !request.isObject()) {
                return CustomerAnswer.problem(Problem.of(400, "The request is a JSON object: {from}, or no body."), instance);
            }
            JsonNode named = request.get("from");
            if (named != null && !named.isNull()) {
                if (!named.isTextual() || !(FAILED_STEP.equals(named.asText()) || START.equals(named.asText()))) {
                    return CustomerAnswer.problem(Problem.validation("The retry does not say where to start.", Collections.singletonList(
                        new Problem.FieldError("from", "must be failed_step or start"))), instance);
                }
                from = named.asText();
            }
        }
        Optional<CustomerRuns.Found> found = this.runs.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, CustomerRuns.NO_SUCH_RUN), instance);
        }
        RunRetries.Outcome outcome = this.retries.retry(found.get().run, found.get().job, START.equals(from),
            RunRetries.Actor.client(TenantContext.getClientId()));
        if (!outcome.queued()) {
            return CustomerAnswer.problem(Problem.of(409, outcome.refusal).kind(outcome.refusalKind), instance);
        }
        long tenantId = found.get().row.tenantId;
        Optional<CustomerRunStore.Row> now = this.store.find(tenantId, found.get().row.runId);
        if (!now.isPresent()) {
            throw new IllegalStateException("Run " + runId + " was queued again but cannot be read back.");
        }
        Map<String, Object> summary = this.runs.reviewSummary(found.get());
        return CustomerAnswer.of(202, CustomerViews.run(now.get(), CustomerViews.reviewWordOf(summary.get("reviewStatus"))),
            "/v1/runs/" + now.get().runId);
    }
}
