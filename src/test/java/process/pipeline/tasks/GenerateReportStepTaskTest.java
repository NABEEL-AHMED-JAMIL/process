package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.backing.ReportStudio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Report Studio phase 5 (plan 1.11): generate_report streams its rows to media-service as NDJSON (a head line naming the
 * design and the run, then one object a row), one job a step attempt, follows the job, keeps the PDF with the run, and
 * passes the rows on. A design the checks refuse fails the step, or warns with CONTINUE_WITH_WARNING; the design's fields
 * the rows lack are said first. Synthetic wound clinic rows.
 */
class GenerateReportStepTaskTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String DESIGN = "01M5D0H33C940CWDVTJPBT7C6H";

    @TempDir
    Path dir;

    /** media-service as the step sees it: what it was sent, and the answers it gives. */
    static final class FakeStudio implements ReportStudio {
        final List<String> sent = new ArrayList<>();
        String key;
        Long userId;
        String failCode;
        List<String> missing = List.of();
        int polls;

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public JsonNode generate(long tenantId, Long userId, String userName, String idempotencyKey, Path lines) throws IOException {
            this.key = idempotencyKey;
            this.userId = userId;
            this.sent.addAll(Files.readAllLines(lines, StandardCharsets.UTF_8));
            return JSON.createObjectNode().put("jobId", "J1").put("label", "J-0001").put("status", "QUEUED");
        }

        @Override
        public JsonNode job(long tenantId, String jobId) {
            this.polls++;
            ObjectNode j = JSON.createObjectNode().put("jobId", jobId).put("reportId", "R1").put("reportLabel", "RPT-0007")
                .put("templateVersion", 2);
            if (this.polls < 2) {
                return j.put("status", "RUNNING");
            }
            if (this.failCode != null) {
                return j.put("status", "FAILED").put("errorCode", this.failCode).put("error", "Field 'area_cm2' is not in the data.");
            }
            j.put("status", "SUCCEEDED").put("resultVersion", 1);
            ArrayNode artifacts = j.putArray("artifacts");
            artifacts.addObject().put("format", "PDF").put("name", "Wound clinic weekly · run 2.pdf").put("pages", 3).put("bytes", 9);
            artifacts.addObject().put("format", "HTML").put("name", "Wound clinic weekly · run 2.html");
            j.putObject("checks").put("errors", 0).put("warnings", 1).putArray("firstCodes").add("REPEAT_CAPPED");
            return j;
        }

        @Override
        public void file(long tenantId, String reportId, int version, String format, OutputStream out) throws IOException {
            out.write("%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1));
        }

        @Override
        public JsonNode check(long tenantId, Collection<String> ids, String version, Collection<String> columns) {
            ArrayNode out = JSON.createArrayNode();
            ObjectNode t = out.addObject().put("templateId", DESIGN).put("found", true).put("name", "Wound clinic weekly (step design)");
            ArrayNode gone = t.putArray("missingFields");
            this.missing.forEach(gone::add);
            return out;
        }
    }

    static Map<String, Object> config(Object... kv) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            c.put((String) kv[i], kv[i + 1]);
        }
        return c;
    }

    static Dataset wounds() {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[][] data = {{"P-1042", "Left heel", "3.82"}, {"P-1077", "Right plantar forefoot", "5.69"}, {"P-1093", "Lower abdomen", "1.95"}};
        for (String[] r : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("patient", r[0]);
            row.put("site", r[1]);
            row.put("area_cm2", Double.valueOf(r[2]));
            rows.add(row);
        }
        return Dataset.of(rows);
    }

    @Test
    void theRowsGoAsLinesTheJobIsFollowedAndThePdfIsKeptWithTheRun() throws Exception {
        FakeStudio studio = new FakeStudio();
        StreamTry context = new StreamTry(config("design", "STEP", "templateId", DESIGN, "templateVersion", "latest",
            "reportName", "Wound clinic weekly · run {{run}}", "period", "{{date}}"), wounds(), 64L << 20, this.dir, 2);
        context.jobId = 77L;
        context.pipelineName = "  Wound clinic weekly  ";

        var result = new GenerateReportStepTask(studio, d -> { }).stream(context);

        assertThat(result.getRecordsOut()).isEqualTo(3);
        assertThat(studio.key).as("one job a step attempt").isEqualTo("pipeline#2#step#a1");
        JsonNode head = JSON.readTree(studio.sent.get(0)).path("head");
        assertThat(head.path("templateId").asText()).isEqualTo(DESIGN);
        assertThat(head.path("version").asText()).isEqualTo("latest");
        assertThat(head.path("name").asText()).isEqualTo("Wound clinic weekly · run 2");
        assertThat(head.path("pipeline").path("jobQueueId").asLong()).isEqualTo(2);
        assertThat(head.path("pipeline").path("jobId").asLong()).isEqualTo(77);
        assertThat(head.path("pipeline").path("stepKey").asText()).isEqualTo("step");
        assertThat(head.path("pipeline").path("pipelineName").asText()).as("the lineage names the pipeline, trimmed")
            .isEqualTo("Wound clinic weekly");
        assertThat(studio.sent).hasSize(4);
        assertThat(JSON.readTree(studio.sent.get(3)).path("patient").asText()).isEqualTo("P-1093");
        assertThat(studio.polls).as("followed until done").isEqualTo(2);
        assertThat(context.files).containsKey("Wound_clinic_weekly_run_2.pdf");
        RunOutput pdf = context.recorded.get(0);
        assertThat(pdf.getFormat()).isEqualTo("pdf");
        assertThat(pdf.getPages()).isEqualTo(3);
        assertThat(context.lines).anyMatch(l -> l.contains("Report RPT-0007 version 1") && l.contains("REPEAT_CAPPED"));
    }

    /** A run whose pipeline has no name sends none (media then keeps none); a long one is cut to 160 characters. */
    @Test
    void theHeadNamesThePipelineOnlyWhenItHasAName() throws Exception {
        for (String none : new String[] {null, "", "   "}) {
            FakeStudio studio = new FakeStudio();
            Path at = Files.createDirectories(this.dir.resolve("none" + (none == null ? "" : none.length())));
            StreamTry context = new StreamTry(config("templateId", DESIGN), wounds(), 64L << 20, at, 2);
            context.pipelineName = none;
            new GenerateReportStepTask(studio, d -> { }).stream(context);
            JsonNode pipeline = JSON.readTree(studio.sent.get(0)).path("head").path("pipeline");
            assertThat(pipeline.has("pipelineName")).as("no name for %s", none == null ? "null" : "'" + none + "'").isFalse();
            assertThat(pipeline.path("jobQueueId").asLong()).isEqualTo(2);
            assertThat(pipeline.path("attempt").asInt()).isEqualTo(1);
        }

        FakeStudio studio = new FakeStudio();
        StreamTry context = new StreamTry(config("templateId", DESIGN), wounds(), 64L << 20, Files.createDirectories(this.dir.resolve("long")), 2);
        context.pipelineName = "W".repeat(200);
        new GenerateReportStepTask(studio, d -> { }).stream(context);
        assertThat(JSON.readTree(studio.sent.get(0)).path("head").path("pipeline").path("pipelineName").asText()).isEqualTo("W".repeat(160));
    }

    @Test
    void aDesignTheChecksRefuseFailsTheStepOrWarnsWhenTheStepSaysSo() throws Exception {
        FakeStudio studio = new FakeStudio();
        studio.failCode = "DATA_SCHEMA_MISMATCH";
        studio.missing = List.of("area_cm2");
        StreamTry failing = new StreamTry(config("templateId", DESIGN), wounds(), 64L << 20, this.dir, 2);
        assertThatThrownBy(() -> new GenerateReportStepTask(studio, d -> { }).stream(failing))
            .hasMessageContaining("DATA_SCHEMA_MISMATCH").hasMessageContaining("area_cm2");
        assertThat(failing.lines).anyMatch(l -> l.startsWith("WARN") && l.contains("reads area_cm2"));

        FakeStudio again = new FakeStudio();
        again.failCode = "DATA_SCHEMA_MISMATCH";
        StreamTry warning = new StreamTry(config("templateId", DESIGN, "onValidationError", "CONTINUE_WITH_WARNING"), wounds(), 64L << 20,
            this.dir.resolve("w"), 2);
        Files.createDirectories(this.dir.resolve("w"));
        assertThat(new GenerateReportStepTask(again, d -> { }).stream(warning).getRecordsOut()).isEqualTo(3);
        assertThat(warning.recorded).isEmpty();
        assertThat(warning.lines).anyMatch(l -> l.startsWith("WARN") && l.contains("goes on without a report"));

        FakeStudio busy = new FakeStudio();
        busy.failCode = "OUTPUT_STORAGE_FAILED";
        StreamTry transientFail = new StreamTry(config("templateId", DESIGN, "onValidationError", "CONTINUE_WITH_WARNING"), wounds(), 64L << 20,
            this.dir.resolve("b"), 2);
        Files.createDirectories(this.dir.resolve("b"));
        assertThatThrownBy(() -> new GenerateReportStepTask(busy, d -> { }).stream(transientFail)).as("not a check: the step's tries decide")
            .hasMessageContaining("OUTPUT_STORAGE_FAILED");
    }

    @Test
    void aStepWithoutADesignSaysWhatToDoAndAVersionIsLatestOrANumber() {
        assertThatThrownBy(() -> new GenerateReportStepTask(new FakeStudio(), d -> { }).stream(new StreamTry(config(), wounds(), 64L << 20, this.dir, 2)))
            .hasMessageContaining("design the report in the step, or pick a template");
        GenerateReportStepTask task = new GenerateReportStepTask(new FakeStudio(), d -> { });
        assertThat(task.check(config("templateVersion", "latest"))).isEmpty();
        assertThat(task.check(config("templateVersion", "3"))).isEmpty();
        assertThat(task.check(config("templateVersion", "v3"))).hasSize(1);
        assertThat(GenerateReportStepTask.safeName("Wound clinic weekly · run 2.pdf")).isEqualTo("Wound_clinic_weekly_run_2.pdf");
    }
}
