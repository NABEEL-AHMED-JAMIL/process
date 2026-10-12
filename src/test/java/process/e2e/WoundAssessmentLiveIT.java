package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-255, the first customer pipeline end to end against the running stack, with synthetic cases only: wound photos in
 * a workspace bucket, a history of earlier visits, and an intake batch dropped in the workspace's inbox.
 *
 * Opt-in: skipped unless PROCESS_LIVE_BASE_URL (the gateway's API root, http://localhost:9098/api/v1) and
 * PROCESS_LIVE_TOKEN (a TENANT_ADMIN of the workspace) are set, with LIVE_WOUND_PIPELINE_KEY and LIVE_WOUND_JOB_ID (a step
 * pipeline and its job, whose inbox trigger matches wound-intake*.csv), LIVE_WOUND_PROMPT_ID (the assessment prompt on a
 * vision model) and LIVE_WOUND_BUCKET (a bucket alias of the workspace). Run with
 * {@code mvn -o verify -Pit -Dit.test=WoundAssessmentLiveIT}. Nothing here prints the token.
 *
 * <ol>
 *   <li>The definition (src/test/resources/e2e/wound) is saved to the pipeline; the photos and the history are uploaded
 *       under wound-e2e/; the intake batch goes to the inbox, and its arrival starts the job.</li>
 *   <li>The run completes through the step engine: the batch's bad row is dropped by wound_intake_rows, each photo is
 *       assessed by the model (DRAFT), and area, change and trend are computed in code.</li>
 *   <li>The outputs agree: the CSV and the JSON hold the same values, the PDF is a PDF, every row is DRAFT, sizes exist
 *       exactly where a ruler was seen, and the trend follows the change.</li>
 *   <li>The results wait for the internal review (PENDING).</li>
 *   <li>Accuracy against the labelled set: the ruler is found on every photo that has one, and the sizes are measured in
 *       code (measure_image) within 10% of the drawn truth (area 15%); a photo without a ruler gets no size. The model
 *       only describes the wound (owner decision 2026-09-29: it over-estimated sizes by 40-68%).</li>
 * </ol>
 * Test data is left in place: the uploaded objects, the saved definition version and the run.
 */
class WoundAssessmentLiveIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    private static final Duration RUN_BUDGET = Duration.ofMinutes(10);
    private static final String PREFIX = "wound-e2e/";

    /** The labelled set: each synthetic photo's truth (the ellipse drawn is the wound; 40 px is a cm on its ruler). */
    private static final Map<String, Boolean> RULER = new HashMap<>();

    /** The drawn wound bed's length and width in cm (an ellipse). */
    private static final Map<String, double[]> SIZES = new HashMap<>();

    static {
        SIZES.put("WC-0001", new double[] {4.0, 2.67});
        SIZES.put("WC-0002", new double[] {2.5, 1.67});
        RULER.put("WC-0001", true);
        RULER.put("WC-0002", true);
        RULER.put("WC-0003", false);
    }

    private static String base;
    private static String token;
    private static OkHttpClient http;

    @BeforeAll
    static void live() {
        base = System.getenv("PROCESS_LIVE_BASE_URL");
        token = System.getenv("PROCESS_LIVE_TOKEN");
        assumeTrue(base != null && !base.trim().isEmpty() && token != null && !token.trim().isEmpty() && System.getenv("LIVE_WOUND_JOB_ID") != null,
            "PROCESS_LIVE_BASE_URL, PROCESS_LIVE_TOKEN and LIVE_WOUND_* are not set: the live E2E is opt-in");
        base = base.replaceAll("/+$", "");
        http = new OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = WoundAssessmentLiveIT.class.getResourceAsStream("/e2e/wound/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = WoundAssessmentLiveIT.class.getResourceAsStream("/e2e/wound/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static Response send(Request.Builder request) throws IOException {
        return http.newCall(request.header("Authorization", "Bearer " + token).build()).execute();
    }

    private static JsonNode call(String method, String path, String body) throws IOException {
        Request.Builder request = new Request.Builder().url(base + path);
        request.method(method, body == null ? null : RequestBody.create(body, APPLICATION_JSON));
        try (Response response = send(request)) {
            String text = response.body() == null ? "" : response.body().string();
            assertThat(response.code()).as(method + " " + path + " answered " + text).isEqualTo(200);
            JsonNode answer = JSON.readTree(text);
            assertThat(answer.path("status").asText()).as(method + " " + path + ": " + answer.path("message").asText()).isEqualTo("SUCCESS");
            return answer;
        }
    }

    private static JsonNode upload(String url, String fileName, byte[] content, String type) throws IOException {
        RequestBody form = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, RequestBody.create(content, MediaType.get(type))).build();
        try (Response response = send(new Request.Builder().url(url).post(form))) {
            String text = response.body() == null ? "" : response.body().string();
            assertThat(response.code()).as("upload " + fileName + ": " + text).isEqualTo(200);
            JsonNode answer = JSON.readTree(text);
            assertThat(answer.path("status").asText()).as(text).isEqualTo("SUCCESS");
            return answer.path("data");
        }
    }

    /** The definition saved to the pipeline, and the photos and the history of earlier visits uploaded. */
    private static void prepare(long pipelineKey, String promptId, String bucket) throws IOException {
        String definition = resource("wound-assessment.definition.json").replace("\"${PROMPT_ID}\"", promptId)
            .replace("${BUCKET}", bucket).replace("${PREFIX}", PREFIX);
        ObjectNode save = JSON.createObjectNode().put("pipelineKey", pipelineKey).put("format", "json").put("text", definition);
        call("POST", "/pipeline.json/steps/save", JSON.writeValueAsString(save));
        for (String photo : new String[] {"WC-0001.png", "WC-0002.png", "WC-0003.png"}) {
            upload(base + "/storage.json/uploadObject?bucket=" + bucket + "&prefix=" + PREFIX + "images/", photo, bytes(photo), "image/png");
        }
        upload(base + "/storage.json/uploadObject?bucket=" + bucket + "&prefix=" + PREFIX + "history/", "wound_history.csv",
            bytes("wound_history.csv"), "text/csv");
    }

    /** The job's first run after {@code before}, once it has finished, as {run id, status}. */
    private static Object[] awaitRun(long jobId, long before) throws Exception {
        long run = 0;
        String status = "";
        long deadline = System.currentTimeMillis() + RUN_BUDGET.toMillis();
        while (System.currentTimeMillis() < deadline) {
            JsonNode runs = call("GET", "/sourceJob.json/fetchSourceJobQueueListWithJobId?jobId=" + jobId, null).path("data").path("jobQueues");
            if (runs.size() > 0 && runs.get(0).path("jobQueueId").asLong() > before) {
                run = runs.get(0).path("jobQueueId").asLong();
                status = runs.get(0).path("jobStatus").asText();
                if ("Completed".equals(status) || "Failed".equals(status)) {
                    break;
                }
            }
            Thread.sleep(3000);
        }
        return new Object[] {run, status};
    }

    private static byte[] download(long runDatasetId) throws IOException {
        HttpUrl url = HttpUrl.get(base + "/sourceJob.json/runDataset").newBuilder()
            .addQueryParameter("runDatasetId", String.valueOf(runDatasetId)).build();
        try (Response response = send(new Request.Builder().url(url).get())) {
            assertThat(response.code()).isEqualTo(200);
            return response.body().bytes();
        }
    }

    private static long latestRunId(long jobId) throws IOException {
        JsonNode runs = call("GET", "/sourceJob.json/fetchSourceJobQueueListWithJobId?jobId=" + jobId, null).path("data").path("jobQueues");
        return runs.size() == 0 ? 0 : runs.get(0).path("jobQueueId").asLong();
    }

    @Test
    void aSyntheticBatchGoesFromTheInboxToReviewedDraftOutputs() throws Exception {
        long pipelineKey = Long.parseLong(System.getenv("LIVE_WOUND_PIPELINE_KEY"));
        long jobId = Long.parseLong(System.getenv("LIVE_WOUND_JOB_ID"));
        String promptId = System.getenv("LIVE_WOUND_PROMPT_ID");
        String bucket = System.getenv("LIVE_WOUND_BUCKET");

        prepare(pipelineKey, promptId, bucket);
        long before = latestRunId(jobId);
        upload(base + "/storage.json/inbox/upload", "wound-intake-0929.csv",
            resource("wound-intake-0929.csv").replace("${PREFIX}", PREFIX).getBytes(StandardCharsets.UTF_8), "text/csv");

        Object[] finished = awaitRun(jobId, before);
        long run = (Long) finished[0];
        String status = (String) finished[1];
        assertThat(run).as("the inbox arrival started a run of job " + jobId).isGreaterThan(before);
        assertThat(status).as("run " + run).isEqualTo("Completed");

        JsonNode steps = call("GET", "/sourceJob.json/stepExecutions?jobQueueId=" + run, null).path("data").path("steps");
        Map<String, Long> rowsOut = new LinkedHashMap<>();
        for (JsonNode step : steps) {
            assertThat(step.path("status").asText()).as("step " + step.path("key").asText()).isEqualTo("Completed");
            JsonNode datasets = step.path("datasets");
            rowsOut.put(step.path("key").asText(), datasets.size() == 0 ? -1 : datasets.get(0).path("rowCount").asLong());
        }
        assertThat(rowsOut).containsEntry("intake", 4L).as("the batch's bad row is dropped by the contract").containsEntry("check", 3L)
            .containsEntry("assess", 3L).containsEntry("result", 3L);

        JsonNode manifest = call("GET", "/sourceJob.json/runOutputs?jobQueueId=" + run, null).path("data");
        assertThat(manifest.path("reviewStatus").asText()).as("the results wait for the internal review").isEqualTo("PENDING");
        Map<String, Long> files = new LinkedHashMap<>();
        for (JsonNode output : manifest.path("outputs")) {
            // The run's own files; the trend copy in the bucket (the keep step) is listed beside them.
            if ("file".equals(output.path("kind").asText())) {
                files.put(output.path("format").asText(), output.path("runDatasetId").asLong());
            } else if ("keep".equals(output.path("stepKey").asText())) {
                assertThat(output.path("key").asText()).isEqualTo(PREFIX + "results/run-" + run + ".csv");
            }
        }
        assertThat(files).containsKeys("csv", "json", "pdf");

        JsonNode rows = JSON.readTree(download(files.get("json")));
        List<Map<String, String>> csv = csvRows(new String(download(files.get("csv")), StandardCharsets.UTF_8));
        assertThat(new String(download(files.get("pdf")), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(rows.size()).isEqualTo(3);
        assertThat(csv).hasSize(3);

        List<String> sizes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            String caseId = row.path("case_id").asText();
            for (String column : new String[] {"case_id", "area_cm2", "prev_area_cm2", "change_pct", "trend", "status"}) {
                assertThat(same(row.path(column), csv.get(i).get(column))).as(caseId + " " + column + ": JSON " + row.path(column)
                    + ", CSV " + csv.get(i).get(column)).isTrue();
            }
            assertThat(row.path("status").asText()).isEqualTo("DRAFT");
            assertThat(row.path("scale_visible").asBoolean()).as(caseId + ": a ruler is visible (labelled set)").isEqualTo(RULER.get(caseId));
            if (row.path("scale_visible").asBoolean()) {
                // Measured in code against the photo's ruler (measure_image): within 10% of the drawn truth.
                double[] truth = SIZES.get(caseId);
                assertThat(row.path("length_cm").asDouble()).as(caseId + ": length").isCloseTo(truth[0], withinPercentage(10));
                assertThat(row.path("width_cm").asDouble()).as(caseId + ": width").isCloseTo(truth[1], withinPercentage(10));
                assertThat(row.path("area_cm2").asDouble()).as(caseId + ": area").isCloseTo(Math.PI / 4 * truth[0] * truth[1], withinPercentage(15));
            } else {
                assertThat(row.path("area_cm2").isNull()).as(caseId + ": no ruler, no size").isTrue();
                assertThat(row.path("length_cm").isNull()).isTrue();
            }
            String trend = row.path("trend").asText();
            if (row.path("prev_area_cm2").isNull()) {
                assertThat(trend).isEqualTo("baseline");
            } else if (row.path("change_pct").isNull()) {
                assertThat(trend).isEqualTo("not measurable");
            } else {
                double change = row.path("change_pct").asDouble();
                assertThat(trend).isEqualTo(change <= -10 ? "improving" : change >= 10 ? "worsening" : "stable");
            }
            sizes.add(caseId + " " + row.path("length_cm") + " x " + row.path("width_cm") + " cm");
        }
        System.out.println("MIG-255 run " + run + " measured sizes (truth: WC-0001 4.0 x 2.67, WC-0002 2.5 x 1.67, WC-0003 none): " + sizes);
    }

    /** Whether a JSON value and a CSV cell say the same: numbers by value, null as an empty cell. */
    private static boolean same(JsonNode json, String cell) {
        if (json == null || json.isNull() || json.isMissingNode()) {
            return cell == null || cell.isEmpty();
        }
        if (json.isNumber()) {
            return cell != null && !cell.isEmpty() && Double.parseDouble(cell) == json.asDouble();
        }
        return json.asText().equals(cell);
    }

    /** The CSV the run kept (a header line, no quoted commas in this data). */
    private static List<Map<String, String>> csvRows(String text) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String[] header = reader.readLine().split(",", -1);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < header.length; c++) {
                    row.put(header[c], c < cells.length ? cells[c] : "");
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * MIG-325: the nurse's path. A photo taken on a phone goes into the wound form's photo field (a file field named
     * "image", whose first upload the submission names as image_key), the submission starts the job, and the run
     * measures that photo. Opt-in with LIVE_WOUND_FORM_ID, the form that starts LIVE_WOUND_JOB_ID.
     */
    @Test
    void aPhotoSubmittedThroughTheFormIsMeasuredAndWaitsForReview() throws Exception {
        String formId = System.getenv("LIVE_WOUND_FORM_ID");
        assumeTrue(formId != null && !formId.trim().isEmpty(), "LIVE_WOUND_FORM_ID is not set");
        long jobId = Long.parseLong(System.getenv("LIVE_WOUND_JOB_ID"));
        prepare(Long.parseLong(System.getenv("LIVE_WOUND_PIPELINE_KEY")), System.getenv("LIVE_WOUND_PROMPT_ID"),
            System.getenv("LIVE_WOUND_BUCKET"));

        JsonNode photo = upload(base + "/form.json/upload?formId=" + formId.trim() + "&field=image", "WC-0001.png", bytes("WC-0001.png"),
            "image/png");
        assertThat(photo.path("uploadId").asLong()).as("the upload, which the submission names; its key is filled in there").isPositive();
        ObjectNode answers = JSON.createObjectNode().put("case_id", "WC-0001").put("patient_id", "SYN-001")
            .put("visit_date", "2026-09-29").put("wound_site", "left heel");
        answers.putArray("image").add(photo);
        ObjectNode submit = JSON.createObjectNode().put("formId", Long.parseLong(formId.trim()));
        submit.set("answers", answers);
        long before = latestRunId(jobId);
        call("POST", "/form.json/submit", JSON.writeValueAsString(submit));

        Object[] finished = awaitRun(jobId, before);
        long run = (Long) finished[0];
        assertThat(run).as("the submission started a run of job " + jobId).isGreaterThan(before);
        assertThat(finished[1]).as("run " + run).isEqualTo("Completed");
        JsonNode manifest = call("GET", "/sourceJob.json/runOutputs?jobQueueId=" + run, null).path("data");
        assertThat(manifest.path("reviewStatus").asText()).isEqualTo("PENDING");
        long json = 0;
        for (JsonNode output : manifest.path("outputs")) {
            if ("file".equals(output.path("kind").asText()) && "json".equals(output.path("format").asText())) {
                json = output.path("runDatasetId").asLong();
            }
        }
        JsonNode rows = JSON.readTree(download(json));
        assertThat(rows.size()).as("the one submission is one row").isEqualTo(1);
        JsonNode row = rows.get(0);
        assertThat(row.path("case_id").asText()).isEqualTo("WC-0001");
        assertThat(row.path("length_cm").asDouble()).as("measured from the uploaded photo").isCloseTo(4.0, withinPercentage(10));
        assertThat(row.path("width_cm").asDouble()).isCloseTo(2.67, withinPercentage(10));

        JsonNode waiting = call("GET", "/sourceJob.json/review/waiting", null).path("data").path("runs");
        boolean listed = false;
        for (JsonNode w : waiting) {
            listed |= w.path("jobQueueId").asLong() == run;
        }
        assertThat(listed).as("run " + run + " is on the reviewer's list").isTrue();
        System.out.println("MIG-325 run " + run + " from form " + formId.trim() + ": " + row.path("length_cm") + " x " + row.path("width_cm") + " cm");
    }
}
