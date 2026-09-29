package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/**
 * integration-service's API runner, as the step engine calls it (MIG-231: Read API, Enrich). The runner is
 * integration-service's -- auth, pagination, retries of the call, masking, the call log -- and Core never repeats it:
 * a step hands over the workspace's saved request and its variables and takes the answer.
 */
public interface ApiRunner {

    /** Why the runner cannot be called from here now; empty when it can. */
    Optional<String> unavailable();

    ApiRunResult run(ApiCall call) throws Exception;

    /** One call: the workspace's saved request (by id), optionally in an environment and at a pinned version. */
    final class ApiCall {
        public long tenantId;
        public long jobQueueId;
        public String stepKey;
        public long requestId;
        public Long environmentId;
        public Integer version;
        public Map<String, String> variables;
    }

    /** integration-service's RunResult, as much of it as a step reads. */
    final class ApiRunResult {
        /** OK, FAILED or BLOCKED. */
        public String outcome;
        public String message;
        public Integer statusCode;
        public JsonNode body;
        /** Every page's items, when the request pages. */
        public JsonNode items;
        public boolean truncated;

        public boolean ok() {
            return "OK".equals(this.outcome);
        }
    }
}
