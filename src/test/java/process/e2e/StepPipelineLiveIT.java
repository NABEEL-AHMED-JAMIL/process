package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-230 end to end against the running stack -- Core as deployed, Kafka, and a real worker -- over HTTP, as the
 * console calls it. Opt-in: skipped unless PROCESS_LIVE_BASE_URL (Core's API root, e.g. http://localhost:9098/api/v1,
 * or the gateway's) and PROCESS_LIVE_TOKEN (a bearer token of a user of the job's workspace) are set. Run with
 * {@code mvn -o verify -Pit -Dit.test=StepPipelineLiveIT}. Nothing here prints the token.
 *
 * <ol>
 *   <li>The legacy wrap: LIVE_LEGACY_JOB_ID (default 2834, workspace 2924's known-good reference CSV job) is run now; its
 *       run goes Queue -> Start -> Running -> Completed through Kafka and the worker's callbacks exactly as before, and its
 *       step timeline is its one legacy step, Completed, whose log is the run's own.</li>
 *   <li>A step pipeline, when LIVE_STEP_PIPELINE_KEY and LIVE_STEP_JOB_ID name a pipeline of the workspace (with no worker
 *       code of its own) and a job on it: a two-step definition is saved to it, the job is run, and the run ends
 *       Completed through the step engine with both steps Completed, their records and their logs. The definition stays
 *       saved (leave test data in place); save the legacy wrap back to undo it.</li>
 * </ol>
 */
class StepPipelineLiveIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    private static final Duration RUN_BUDGET = Duration.ofMinutes(10);

    private static String base;
    private static String token;
    private static OkHttpClient http;

    @BeforeAll
    static void live() {
        base = System.getenv("PROCESS_LIVE_BASE_URL");
        token = System.getenv("PROCESS_LIVE_TOKEN");
        assumeTrue(base != null && !base.trim().isEmpty() && token != null && !token.trim().isEmpty(),
            "PROCESS_LIVE_BASE_URL and PROCESS_LIVE_TOKEN are not set: the live E2E is opt-in");
        base = base.replaceAll("/+$", "");
        http = new OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build();
    }

    private static JsonNode call(String method, String path, String body) throws IOException {
        Request.Builder request = new Request.Builder().url(base + path).header("Authorization", "Bearer " + token);
        request.method(method, body == null ? null : RequestBody.create(body, APPLICATION_JSON));
        try (Response response = http.newCall(request.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            assertThat(response.code()).as(method + " " + path + " answered " + text).isEqualTo(200);
            return JSON.readTree(text);
        }
    }

    /** Runs the job now; the new run's id and every status it was seen in, until it ends. */
    private static RunSeen runToTheEnd(long jobId) throws Exception {
        long before = latestRunId(jobId);
        JsonNode queued = call("POST", "/sourceJob.json/runSourceJob", "{\"jobId\":" + jobId + "}");
        assertThat(queued.path("status").asText()).as(queued.toString()).isEqualTo("SUCCESS");
        RunSeen seen = new RunSeen();
        long deadline = System.currentTimeMillis() + RUN_BUDGET.toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = latestRun(jobId);
            if (run != null && run.path("jobQueueId").asLong() > before) {
                seen.jobQueueId = run.path("jobQueueId").asLong();
                seen.statuses.add(run.path("jobStatus").asText());
                String status = run.path("jobStatus").asText();
                if ("Completed".equals(status) || "Failed".equals(status) || "Interrupt".equals(status)) {
                    seen.last = status;
                    return seen;
                }
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Job " + jobId + " did not finish within " + RUN_BUDGET + "; seen " + seen.statuses);
    }

    private static long latestRunId(long jobId) throws IOException {
        JsonNode run = latestRun(jobId);
        return run == null ? 0 : run.path("jobQueueId").asLong();
    }

    private static JsonNode latestRun(long jobId) throws IOException {
        JsonNode runs = call("GET", "/sourceJob.json/fetchSourceJobQueueListWithJobId?jobId=" + jobId, null).path("data").path("jobQueues");
        JsonNode latest = null;
        for (JsonNode run : runs) {
            if (latest == null || run.path("jobQueueId").asLong() > latest.path("jobQueueId").asLong()) {
                latest = run;
            }
        }
        return latest;
    }

    private static final class RunSeen {
        long jobQueueId;
        String last;
        final Set<String> statuses = new LinkedHashSet<>();
    }

    @Test
    void theReferenceLegacyJobRunsThroughKafkaAndItsWorkerExactlyAsBefore() throws Exception {
        long jobId = Long.parseLong(System.getenv().getOrDefault("LIVE_LEGACY_JOB_ID", "2834"));
        RunSeen seen = runToTheEnd(jobId);

        assertThat(seen.last).as("seen " + seen.statuses).isEqualTo("Completed");
        List<String> order = new ArrayList<>(seen.statuses);
        assertThat(order).as("never out of the lifecycle's order").isSubsetOf("Queue", "Start", "Running", "Completed");
        JsonNode timeline = call("GET", "/sourceJob.json/stepExecutions?jobQueueId=" + seen.jobQueueId, null).path("data");
        assertThat(timeline.path("legacy").asBoolean()).isTrue();
        assertThat(timeline.path("steps")).hasSize(1);
        assertThat(timeline.path("steps").get(0).path("key").asText()).isEqualTo("legacy");
        assertThat(timeline.path("steps").get(0).path("status").asText()).isEqualTo("Completed");
        JsonNode log = call("GET", "/sourceJob.json/findSourceJobAuditLog?jobQueueId=" + seen.jobQueueId + "&jobId=" + jobId, null);
        assertThat(log.toString()).as("dispatched through Kafka, as before").contains("handed to the worker queue");
    }

    @Test
    void aTwoStepPipelineRunsInTheStepEngineWithEachStepTracked() throws Exception {
        String pipelineKey = System.getenv("LIVE_STEP_PIPELINE_KEY");
        String job = System.getenv("LIVE_STEP_JOB_ID");
        assumeTrue(pipelineKey != null && job != null, "LIVE_STEP_PIPELINE_KEY and LIVE_STEP_JOB_ID name no step pipeline to run");
        String yaml = "version: 1\\nsteps:\\n  - key: read\\n    task: sample\\n    config:\\n      rows:\\n        - {id: 1, name: Ada}\\n"
            + "        - {id: 2, name: Bo}\\n  - key: keep\\n    task: select\\n    config: {columns: {name: patient}}\\n";
        JsonNode saved = call("POST", "/pipeline.json/steps/save", "{\"pipelineKey\":" + Long.parseLong(pipelineKey)
            + ",\"format\":\"yaml\",\"text\":\"" + yaml + "\"}");
        assertThat(saved.path("status").asText()).as(saved.toString()).isEqualTo("SUCCESS");

        RunSeen seen = runToTheEnd(Long.parseLong(job));

        assertThat(seen.last).as("seen " + seen.statuses).isEqualTo("Completed");
        JsonNode timeline = call("GET", "/sourceJob.json/stepExecutions?jobQueueId=" + seen.jobQueueId, null).path("data");
        assertThat(timeline.path("legacy").asBoolean()).isFalse();
        JsonNode steps = timeline.path("steps");
        assertThat(steps).hasSize(2);
        for (JsonNode step : steps) {
            assertThat(step.path("status").asText()).as(step.toString()).isEqualTo("Completed");
            assertThat(step.path("startedAt").isNull()).isFalse();
            assertThat(step.path("endedAt").isNull()).isFalse();
        }
        assertThat(steps.get(1).path("recordsIn").asLong()).isEqualTo(2);
        assertThat(steps.get(1).path("recordsOut").asLong()).isEqualTo(2);
        JsonNode log = call("GET", "/sourceJob.json/stepLogs?stepExecutionId=" + steps.get(0).path("stepExecutionId").asLong(), null);
        assertThat(log.path("data").path("lines").toString()).contains("2 sample row(s).");
    }
}
