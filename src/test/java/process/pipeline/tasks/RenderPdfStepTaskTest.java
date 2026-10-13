package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.backing.ReportLines;
import process.pipeline.backing.ReportRenderer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-255: Render PDF turns the rows it reads into a report (media-service renders it; Core never draws a page) and keeps
 * it with the run as a pdf output, like a Save File: the title and header fields fill from the run, the chosen columns in
 * their order, and the rows pass on unchanged. A retried step bills once: its usage key is the run's step and attempt.
 */
class RenderPdfStepTaskTest {

    /** A report as media-service would read its lines. */
    static final class Asked {
        long tenantId;
        String usageKey;
        JsonNode head;
        final List<Table> tables = new ArrayList<>();

        Table table(int i) {
            return this.tables.get(i);
        }
    }

    static final class Table {
        String title;
        String note;
        String trailer;
        List<String> columns = new ArrayList<>();
        final List<List<Object>> rows = new ArrayList<>();
    }

    /** The renderer as the step sees it: what it was asked (its lines read back), and a small PDF of two pages. */
    static final class Renderer implements ReportRenderer {
        private static final ObjectMapper JSON = new ObjectMapper();
        final List<Asked> asked = new ArrayList<>();
        String unavailable;
        RuntimeException refuse;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public Rendered render(long tenantId, String usageKey, ReportLines lines, OutputStream pdf) throws Exception {
            if (this.refuse != null) {
                throw this.refuse;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            lines.writeTo(body);
            Asked asked = new Asked();
            asked.tenantId = tenantId;
            asked.usageKey = usageKey;
            Table table;
            long rows = 0;
            for (String line : body.toString(StandardCharsets.UTF_8.name()).split("\n")) {
                JsonNode node = JSON.readTree(line);
                if (asked.head == null) {
                    asked.head = node;
                } else if (node.has("section")) {
                    JsonNode s = node.get("section");
                    table = new Table();
                    table.title = s.hasNonNull("title") ? s.get("title").asText() : null;
                    table.note = s.hasNonNull("note") ? s.get("note").asText() : null;
                    table.trailer = s.hasNonNull("trailer") ? s.get("trailer").asText() : null;
                    for (JsonNode c : s.get("columns")) {
                        table.columns.add(c.asText());
                    }
                    asked.tables.add(table);
                } else {
                    List<Object> values = new ArrayList<>();
                    for (JsonNode cell : node) {
                        values.add(cell.isNull() ? null : cell.isNumber() ? (Object) cell.numberValue() : cell.isBoolean() ? cell.asBoolean() : cell.asText());
                    }
                    asked.tables.get(asked.tables.size() - 1).rows.add(values);
                    rows++;
                }
            }
            this.asked.add(asked);
            pdf.write("%PDF-1.7 report".getBytes(StandardCharsets.UTF_8));
            return new Rendered(2, rows);
        }
    }

    private final Renderer renderer = new Renderer();
    private final RenderPdfStepTask task = new RenderPdfStepTask(this.renderer);

    private TaskContext context(Object... more) {
        Object[] base = {"fileName", "wound-report", "title", "Wound assessment, run {{run}}",
            "fields", config("Status", "DRAFT for clinician review"), "columns", Arrays.asList("case_id", "trend")};
        Object[] settings = new Object[base.length + more.length];
        System.arraycopy(base, 0, settings, 0, base.length);
        System.arraycopy(more, 0, settings, base.length, more.length);
        return TaskContext.of(config(settings), Arrays.asList(row("case_id", "C1", "trend", "improving", "ai_output", "{...}"),
            row("case_id", "C2", "trend", "baseline", "ai_output", "{...}")));
    }

    @Test
    void theRowsBecomeAReportKeptWithTheRunAsAPdfOutput() throws Exception {
        TaskContext context = this.context();

        Dataset out = this.task.run(context).getOutput();

        Asked asked = this.renderer.asked.get(0);
        assertThat(asked.tenantId).isEqualTo(TaskContext.TENANT);
        assertThat(asked.usageKey).isEqualTo("pipeline#" + TaskContext.RUN + "#step#a1");
        assertThat(asked.head.path("title").asText()).isEqualTo("Wound assessment, run " + TaskContext.RUN);
        assertThat(asked.head.path("fields").path("Status").asText()).isEqualTo("DRAFT for clinician review");
        assertThat(asked.head.path("orientation").asText()).as("two columns fit a portrait page").isEqualTo("portrait");
        assertThat(asked.head.has("watermark")).isFalse();
        assertThat(asked.tables).hasSize(1);
        assertThat(asked.table(0).title).as("a plain report's one table has no title of its own").isNull();
        assertThat(asked.table(0).columns).containsExactly("case_id", "trend");
        assertThat(asked.table(0).rows).containsExactly(Arrays.<Object>asList("C1", "improving"), Arrays.<Object>asList("C2", "baseline"));
        assertThat(asked.table(0).trailer).isEqualTo("2 rows.");
        assertThat(context.files).containsOnlyKeys("wound-report.pdf");
        RunOutput output = context.recorded.get(0);
        assertThat(output.getName()).isEqualTo("wound-report.pdf");
        assertThat(output.getFormat()).isEqualTo("pdf");
        assertThat(output.getRows()).isEqualTo(2);
        assertThat(output.getPages()).isEqualTo(2);
        assertThat(output.getSha256()).hasSize(64);
        assertThat(out == null || out.size() == 2).as("the rows pass on").isTrue();
    }

    /** A wide table turns the page; a watermark (DRAFT) fills from the run like the title. */
    @Test
    void aWideTableIsLandscapeAndAWatermarkIsPrinted() throws Exception {
        TaskContext context = TaskContext.of(config("fileName", "wide", "watermark", "DRAFT"), Arrays.asList(
            row("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6, "g", 7)));
        this.task.run(context);
        assertThat(this.renderer.asked.get(0).head.path("orientation").asText()).isEqualTo("landscape");
        assertThat(this.renderer.asked.get(0).head.path("watermark").asText()).isEqualTo("DRAFT");
        this.task.run(TaskContext.of(config("fileName", "tall", "orientation", "portrait"), Arrays.asList(
            row("a", 1, "b", 2, "c", 3, "d", 4, "e", 5, "f", 6, "g", 7))));
        assertThat(this.renderer.asked.get(1).head.path("orientation").asText()).isEqualTo("portrait");
    }

    @Test
    void aHeaderLabelIsPlainText() {
        assertThat(this.task.check(config("fileName", "r", "fields", config("Status", "ok", "<b>x</b>", "no", "{{x}}", "no"))))
            .extracting(p -> p.getPath()).containsExactly("fields.<b>x</b>", "fields.{{x}}");
    }

    @Test
    void aColumnTheRowsDoNotHaveIsAnErrorAndNothingIsRendered() {
        assertThatThrownBy(() -> this.task.run(this.context("columns", Arrays.asList("case_id", "stage"))))
            .hasMessage("The rows have no column 'stage'.");
        assertThat(this.renderer.asked).isEmpty();
        this.renderer.unavailable = "media-service does not render for pipelines yet";
        assertThat(this.task.unavailable()).contains("media-service does not render for pipelines yet");
    }

    @Test
    void aRefusedRenderFailsTheStepAndKeepsNoFile() {
        this.renderer.refuse = new IllegalStateException("media-service did not render the report (HTTP 503): every slot is busy");
        TaskContext context = this.context();
        assertThatThrownBy(() -> this.task.run(context)).hasMessageContaining("HTTP 503");
        assertThat(context.files).isEmpty();
        assertThat(context.recorded).isEmpty();
    }

    @Test
    void withoutChosenColumnsEveryColumnIsInTheReport() throws Exception {
        TaskContext context = TaskContext.of(config("fileName", "all.pdf"), Arrays.asList(row("a", 1, "b", 2)));
        this.task.run(context);
        Map<String, byte[]> files = context.files;
        assertThat(files).containsKey("all.pdf");
        assertThat(this.renderer.asked.get(0).table(0).columns).containsExactly("a", "b");
    }

    @Test
    void anEmptyInputIsAReportThatSaysSo() throws Exception {
        TaskContext context = TaskContext.of(config("fileName", "none"), new ArrayList<>());
        this.task.run(context);
        assertThat(this.renderer.asked.get(0).table(0).rows).isEmpty();
        assertThat(context.recorded.get(0).getRows()).isZero();
    }
}
