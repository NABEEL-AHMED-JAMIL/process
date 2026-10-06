package process.pipeline.backing;

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
import process.pipeline.data.Values;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The pipeline's calls to integration-service (MIG-231), service to service: X-Internal-Token, the run's workspace
 * named in the body (the engine takes it from the run, never from a request), under /api/v1/internal/pipelines. The
 * worker paths (/apiCollection.json/request/run, /dataContract.json/validate/run) are not usable here: they take the
 * run's own callback token, which Core keeps only as a hash.
 *
 * integration-service does not have these endpoints yet; this is Core's side of the contract, written against the
 * spec in MIG-231's hand-off. Until it ships, process.pipeline.integration.internal-endpoints is false and the tasks
 * are listed as unavailable; Write Database has its own switch (process.pipeline.integration.database-write), off.
 *
 * <pre>
 * POST /api/v1/internal/pipelines/api/run          {tenantId, jobQueueId, stepKey, requestId, environmentId, version, variables}
 *      -> ApiResponse{data: RunResult}
 * POST /api/v1/internal/pipelines/contract/validateRows {tenantId, jobQueueId, stepKey, contractId, contractName, version, rows[]}
 *      -> ApiResponse{data: {contractId, name, version, results: [{index, valid, errorCount, errors: [{path, keyword, message}]}]}}
 * POST /api/v1/internal/pipelines/database/query   {tenantId, jobQueueId, stepKey, connectionId, query, maxRows}
 *      -> ApiResponse{data: {columns, rows, truncated}}
 * POST /api/v1/internal/pipelines/database/write   {tenantId, jobQueueId, stepKey, connectionId, table, mode, keyColumns, columns, rows[]}
 *      -> ApiResponse{data: {written}}
 * </pre>
 */
@Component
public class HttpIntegrationPipelines implements ApiRunner, ContractChecker, DatabaseReader {

    static final String BASE = "/api/v1/internal/pipelines";
    private static final MediaType JSON = MediaType.get("application/json");

    private final String base;
    private final String serviceToken;
    private final boolean endpoints;
    private final boolean databaseWrite;
    private final ObjectMapper json = Values.JSON;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build();

    @Autowired
    public HttpIntegrationPipelines(@Value("${integration.url:http://integration:9180}") String integrationUrl,
        @Value("${internal.service-token:}") String serviceToken,
        @Value("${process.pipeline.integration.internal-endpoints:false}") boolean endpoints,
        @Value("${process.pipeline.integration.database-write:false}") boolean databaseWrite) {
        this.base = integrationUrl.replaceAll("/+$", "") + BASE;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.endpoints = endpoints;
        this.databaseWrite = databaseWrite;
    }

    @Override
    public Optional<String> unavailable() {
        if (!this.endpoints) {
            return Optional.of("integration-service's internal pipeline endpoints are not deployed yet "
                + "(process.pipeline.integration.internal-endpoints is off)");
        }
        if (this.serviceToken.isEmpty()) {
            return Optional.of("Core has no service token for integration-service (internal.service-token)");
        }
        return Optional.empty();
    }

    /** Write Database's own availability: the endpoints, and its own switch. */
    public Optional<String> writeUnavailable() {
        if (!this.databaseWrite) {
            // Owner decision 2026-09-29: pipelines do not write into customer databases (may be added later).
            return Optional.of("Writing to a database is not part of this release: database connections are read-only.");
        }
        return this.unavailable();
    }

    // ---- ApiRunner ---------------------------------------------------------------------------------------------

    @Override
    public ApiRunResult run(ApiCall call) throws IOException {
        ObjectNode body = this.json.createObjectNode();
        this.context(body, call.tenantId, call.jobQueueId, call.stepKey);
        body.put("requestId", call.requestId);
        body.putPOJO("environmentId", call.environmentId);
        body.putPOJO("version", call.version);
        body.set("variables", this.json.valueToTree(call.variables == null ? new LinkedHashMap<>() : call.variables));
        JsonNode data = this.post("/api/run", body);
        ApiRunResult result = new ApiRunResult();
        result.outcome = data.path("outcome").asText(null);
        result.message = data.path("message").asText(null);
        result.statusCode = data.hasNonNull("statusCode") ? data.get("statusCode").asInt() : null;
        result.body = data.get("body");
        result.items = data.get("items");
        result.truncated = data.path("truncated").asBoolean(false);
        return result;
    }

    // ---- ContractChecker --------------------------------------------------------------------------------------

    @Override
    public ContractVerdicts validate(ContractCall call) throws IOException {
        ObjectNode body = this.json.createObjectNode();
        this.context(body, call.tenantId, call.jobQueueId, call.stepKey);
        body.putPOJO("contractId", call.contractId);
        body.putPOJO("contractName", call.contractName);
        body.putPOJO("version", call.version);
        body.set("rows", this.json.valueToTree(call.rows));
        JsonNode data = this.post("/contract/validateRows", body);
        ContractVerdicts verdicts = new ContractVerdicts();
        verdicts.contractId = data.hasNonNull("contractId") ? data.get("contractId").asLong() : null;
        verdicts.name = data.path("name").asText(null);
        verdicts.version = data.hasNonNull("version") ? data.get("version").asInt() : null;
        verdicts.rows = new ArrayList<>();
        for (JsonNode result : data.path("results")) {
            List<String> errors = new ArrayList<>();
            for (JsonNode error : result.path("errors")) {
                String path = error.path("path").asText("");
                errors.add((path.isEmpty() ? "" : path + ": ") + error.path("message").asText(""));
            }
            verdicts.rows.add(new RowVerdict(result.path("index").asInt(), result.path("valid").asBoolean(), errors));
        }
        return verdicts;
    }

    // ---- DatabaseReader ---------------------------------------------------------------------------------------

    @Override
    public QueryResult query(QueryCall call) throws IOException {
        ObjectNode body = this.json.createObjectNode();
        this.context(body, call.tenantId, call.jobQueueId, call.stepKey);
        body.put("connectionId", call.connectionId);
        body.put("query", call.query);
        body.put("maxRows", call.maxRows);
        JsonNode data = this.post("/database/query", body);
        QueryResult result = new QueryResult();
        result.columns = new ArrayList<>();
        for (JsonNode column : data.path("columns")) {
            result.columns.add(column.asText());
        }
        result.rows = new ArrayList<>();
        for (JsonNode row : data.path("rows")) {
            Map<String, Object> values = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                values.put(field.getKey(), Values.scalar(field.getValue()));
            }
            result.rows.add(values);
        }
        result.truncated = data.path("truncated").asBoolean(false);
        return result;
    }

    // ---- DatabaseWriter (through IntegrationDatabaseWriter, which has its own availability) --------------------

    public long write(DatabaseWriter.WriteCall call) throws IOException {
        Optional<String> unavailable = this.writeUnavailable();
        if (unavailable.isPresent()) {
            throw new IllegalStateException("Write Database is not available: " + unavailable.get() + ".");
        }
        ObjectNode body = this.json.createObjectNode();
        this.context(body, call.tenantId, call.jobQueueId, call.stepKey);
        body.put("connectionId", call.connectionId);
        body.put("table", call.table);
        body.put("mode", call.mode);
        body.set("keyColumns", this.json.valueToTree(call.keyColumns));
        body.set("columns", this.json.valueToTree(call.columns));
        ArrayNode rows = this.json.valueToTree(call.rows);
        body.set("rows", rows);
        return this.post("/database/write", body).path("written").asLong();
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------

    private void context(ObjectNode body, long tenantId, long jobQueueId, String stepKey) {
        body.put("tenantId", tenantId);
        body.put("jobQueueId", jobQueueId);
        body.put("stepKey", stepKey);
    }

    /** The envelope's data; its ERROR as a refusal in integration-service's words. */
    private JsonNode post(String path, ObjectNode body) throws IOException {
        Optional<String> unavailable = this.unavailable();
        if (unavailable.isPresent()) {
            throw new IllegalStateException(unavailable.get() + ".");
        }
        Request request = new Request.Builder().url(this.base + path).header("X-Internal-Token", this.serviceToken)
            .header("Accept", "application/json").post(RequestBody.create(this.json.writeValueAsBytes(body), JSON)).build();
        try (Response response = this.http.newCall(request).execute()) {
            ResponseBody content = response.body();
            String text = content == null ? "" : content.string();
            JsonNode answer = text.isEmpty() ? null : this.readOrNull(text);
            if (response.code() == 401 || response.code() == 403) {
                throw new IllegalStateException("integration-service refused Core's service token.");
            }
            if (response.code() == 404 && (answer == null || !answer.hasNonNull("message"))) {
                throw new IllegalStateException("integration-service has no " + BASE + path + " endpoint.");
            }
            String message = answer != null && answer.hasNonNull("message") ? answer.get("message").asText() : null;
            if (answer != null && "ERROR".equals(answer.path("status").asText())) {
                throw new IllegalArgumentException(message == null ? "integration-service refused the call." : message);
            }
            if (response.code() != 200 || answer == null) {
                throw new IllegalStateException(message != null ? message : "integration-service answered " + response.code() + ".");
            }
            JsonNode data = answer.get("data");
            return data == null ? this.json.createObjectNode() : data;
        }
    }

    private JsonNode readOrNull(String text) {
        try {
            return this.json.readTree(text);
        } catch (IOException notJson) {
            return null;
        }
    }
}
