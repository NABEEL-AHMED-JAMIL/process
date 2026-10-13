package process.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.springframework.stereotype.Service;
import process.backfill.Backfills;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * MIG-347: backfills in the customer API (ADR-025): a pipeline -- the API's word for a schedule -- run once for each period
 * of a past range, a few at a time, with its parameters.
 *
 * <ul>
 *   <li>POST /v1/pipelines/{pipelineId}/backfills {from, to, step?, concurrency?, rerunCompleted?, parameters?}: scope
 *   runs:write, Idempotency-Key required (decision 6); 202 with the Backfill and Location /v1/backfills/{backfillId}.</li>
 *   <li>GET /v1/backfills/{backfillId}: scope runs:read; the Backfill with its progress and its periods (the first 100).</li>
 *   <li>POST /v1/backfills/{backfillId}/cancel: scope runs:write, Idempotency-Key required; 200 with the Backfill.</li>
 * </ul>
 * The console's rules (Backfills): the same periods, caps and refusals, as problem+json -- 400 a request that is not valid,
 * 404 not the workspace's, 409 its state or the workspace's (/problems/too-many-backfills, backfill-overlaps,
 * nothing-to-run, pipeline-inactive, pipeline-not-backfillable, backfill-closed), 422 parameters that do not stand. Costs
 * are not in it: an API client is the customer party, and what the workspace is billed is its administrators' to read.
 */
@Service
public class CustomerBackfills {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("from", "to", "step", "concurrency",
        "rerunCompleted", "parameters"));

    private final Backfills backfills;
    private final Idempotency idempotency;

    public CustomerBackfills(Backfills backfills, Idempotency idempotency) {
        this.backfills = backfills;
        this.idempotency = idempotency;
    }

    public CustomerAnswer start(String pipelineId, byte[] body, String idempotencyKey) {
        String instance = "/v1/pipelines/" + pipelineId + "/backfills";
        if (!TenantContext.hasScope(ApiScopes.RUNS_WRITE)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_WRITE), instance);
        }
        byte[] sent = body == null ? new byte[0] : body;
        if (sent.length > CustomerPipelines.MAX_BODY_BYTES) {
            return CustomerAnswer.problem(Problem.of(413, "A backfill's request is at most 1 MB of JSON."), instance);
        }
        String fingerprint = IdempotencyKeys.fingerprint("POST".getBytes(StandardCharsets.UTF_8), instance.getBytes(StandardCharsets.UTF_8), sent);
        return this.idempotency.once(idempotencyKey, instance, fingerprint, () -> this.started(pipelineId, sent, instance));
    }

    private CustomerAnswer started(String pipelineId, byte[] body, String instance) {
        Long jobId = id(pipelineId);
        if (jobId == null) {
            return CustomerAnswer.problem(Problem.of(404, "No such pipeline."), instance);
        }
        JsonNode request;
        try {
            request = body.length == 0 ? null : JSON.readTree(body);
        } catch (IOException unreadable) {
            return CustomerAnswer.problem(Problem.of(400, "The request is not JSON."), instance);
        }
        if (request == null || !request.isObject()) {
            return CustomerAnswer.problem(Problem.of(400, "The request is a JSON object: {from, to, step, concurrency, rerunCompleted, "
                + "parameters}."), instance);
        }
        Iterator<String> names = request.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                return CustomerAnswer.problem(Problem.validation("The request names a field a backfill does not have.",
                    Collections.singletonList(new Problem.FieldError(name, "not a field of a backfill"))), instance);
            }
        }
        Backfills.Request r = new Backfills.Request();
        r.jobId = jobId;
        r.from = text(request.get("from"));
        r.to = text(request.get("to"));
        r.step = text(request.get("step"));
        JsonNode concurrency = request.get("concurrency");
        if (concurrency != null && !concurrency.isNull()) {
            if (!concurrency.canConvertToInt()) {
                return CustomerAnswer.problem(Problem.validation("The concurrency is a whole number.",
                    Collections.singletonList(new Problem.FieldError("concurrency", "a whole number from 1"))), instance);
            }
            r.concurrency = concurrency.asInt();
        }
        JsonNode rerun = request.get("rerunCompleted");
        r.rerunCompleted = rerun != null && rerun.asBoolean(false);
        JsonNode parameters = request.get("parameters");
        if (parameters != null && !parameters.isNull()) {
            if (!parameters.isObject()) {
                return CustomerAnswer.problem(Problem.validation("parameters is an object of values by name.",
                    Collections.singletonList(new Problem.FieldError("parameters", "an object"))), instance);
            }
            r.parameters = JSON.convertValue(parameters, new TypeReference<Map<String, Object>>() { });
        }
        try {
            Map<String, Object> started = this.backfills.start(r, Backfills.Origin.api(TenantContext.getClientId()));
            Map<String, Object> view = view(started);
            return CustomerAnswer.of(202, view, "/v1/backfills/" + view.get("backfillId"));
        } catch (Backfills.Refused refused) {
            return CustomerAnswer.problem(problem(refused), instance);
        }
    }

    public CustomerAnswer get(String backfillId) {
        String instance = "/v1/backfills/" + backfillId;
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Long id = id(backfillId);
        if (id == null) {
            return CustomerAnswer.problem(Problem.of(404, "No such backfill."), instance);
        }
        try {
            return CustomerAnswer.of(200, view(this.backfills.detail(id, null, 1, true)), null);
        } catch (Backfills.Refused refused) {
            return CustomerAnswer.problem(problem(refused), instance);
        }
    }

    public CustomerAnswer cancel(String backfillId, String idempotencyKey) {
        String instance = "/v1/backfills/" + backfillId + "/cancel";
        if (!TenantContext.hasScope(ApiScopes.RUNS_WRITE)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_WRITE), instance);
        }
        String fingerprint = IdempotencyKeys.fingerprint("POST".getBytes(StandardCharsets.UTF_8), instance.getBytes(StandardCharsets.UTF_8),
            new byte[0]);
        return this.idempotency.once(idempotencyKey, instance, fingerprint, () -> {
            Long id = id(backfillId);
            if (id == null) {
                return CustomerAnswer.problem(Problem.of(404, "No such backfill."), instance);
            }
            try {
                return CustomerAnswer.of(200, view(this.backfills.cancel(id, null, true)), null);
            } catch (Backfills.Refused refused) {
                return CustomerAnswer.problem(problem(refused), instance);
            }
        });
    }

    /** A backfill as the API writes it: ids as strings, its pipeline by the API's word, times as RFC 3339 UTC (as given). */
    static Map<String, Object> view(Map<String, Object> backfill) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backfillId", String.valueOf(backfill.get("backfillId")));
        out.put("pipelineId", String.valueOf(backfill.get("jobId")));
        out.put("pipelineName", backfill.get("jobName"));
        Object status = backfill.get("status");
        out.put("status", status == null ? null : String.valueOf(status).toLowerCase(Locale.ROOT));
        Object step = backfill.get("step");
        out.put("step", step == null ? null : String.valueOf(step).toLowerCase(Locale.ROOT));
        out.put("from", backfill.get("from"));
        out.put("to", backfill.get("to"));
        out.put("timeZone", backfill.get("zone"));
        out.put("concurrency", backfill.get("concurrency"));
        out.put("rerunCompleted", backfill.get("rerunCompleted"));
        out.put("parameters", backfill.get("parameters"));
        out.put("total", backfill.get("total"));
        out.put("counts", backfill.get("counts"));
        out.put("waitingReason", backfill.get("holdReason"));
        out.put("createdAt", backfill.get("dateCreated"));
        out.put("endedAt", backfill.get("endedAt"));
        Object eta = backfill.get("eta");
        out.put("estimatedEndAt", eta instanceof Map ? ((Map<?, ?>) eta).get("at") : null);
        Object periods = backfill.get("periods");
        if (periods instanceof List) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object p : (List<?>) periods) {
                Map<?, ?> period = (Map<?, ?>) p;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", period.get("date"));
                row.put("start", period.get("start"));
                row.put("end", period.get("end"));
                Object state = period.get("state");
                row.put("status", state == null ? null : String.valueOf(state).toLowerCase(Locale.ROOT));
                row.put("runId", period.get("jobQueueId") == null ? null : String.valueOf(period.get("jobQueueId")));
                row.put("note", period.get("note"));
                rows.add(row);
            }
            out.put("periods", rows);
        }
        return out;
    }

    static Problem problem(Backfills.Refused refused) {
        Problem problem = Problem.of(refused.getStatus().value(), refused.getMessage());
        return refused.getStatus().value() == 404 || refused.getKind() == null ? problem : problem.kind(refused.getKind());
    }

    private static Long id(String text) {
        try {
            long id = Long.parseLong(text == null ? "" : text.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException notOurs) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
