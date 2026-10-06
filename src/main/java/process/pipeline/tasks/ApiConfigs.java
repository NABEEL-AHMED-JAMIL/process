package process.pipeline.tasks;

import process.pipeline.StepContext;
import process.pipeline.backing.ApiRunner;
import process.pipeline.registry.JsonSchema;

import java.util.Map;

/** The settings Read API and Enrich share: which saved request, where, at which version. */
final class ApiConfigs {

    private ApiConfigs() {
    }

    static JsonSchema request(JsonSchema schema) {
        return schema
            .required("requestId", JsonSchema.integer().minimum(1).title("API request").format("api-request")
                .description("A request saved in the workspace's API collections (integration-service)."))
            .property("environmentId", JsonSchema.integer().minimum(1).title("Environment").format("api-environment")
                .description("The environment whose variables the request runs with; none for the collection's default."))
            .property("version", JsonSchema.integer().minimum(1).title("Collection version")
                .description("Pin the request to a published collection version; empty for the request as it is now."));
    }

    static ApiRunner.ApiCall call(StepContext context, Map<String, Object> config, Map<String, String> variables) {
        ApiRunner.ApiCall call = new ApiRunner.ApiCall();
        call.tenantId = context.tenantId();
        call.jobQueueId = context.jobQueueId();
        call.stepKey = context.stepKey();
        call.requestId = Configs.longValue(config, "requestId");
        call.environmentId = Configs.longValue(config, "environmentId");
        call.version = Configs.integer(config, "version", null);
        call.variables = variables;
        return call;
    }

    /** The run's failure in the runner's words. */
    static IllegalStateException failed(ApiRunner.ApiRunResult result) {
        return new IllegalStateException(String.format("The API request %s%s%s.", result.outcome == null ? "failed" : result.outcome.toLowerCase(),
            result.statusCode == null ? "" : " (HTTP " + result.statusCode + ")", result.message == null ? "" : ": " + result.message));
    }
}
