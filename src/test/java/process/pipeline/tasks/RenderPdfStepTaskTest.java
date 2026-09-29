package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.backing.ReportRenderer;

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

    /** The renderer as the step sees it: what it was asked, and a small PDF back. */
    static final class Renderer implements ReportRenderer {
        final List<Report> asked = new ArrayList<>();
        String unavailable;

        @Override
        public Optional<String> unavailable() {
            return Optional.ofNullable(this.unavailable);
        }

        @Override
        public byte[] renderPdf(Report request) {
            this.asked.add(request);
            return "%PDF-1.7 report".getBytes(StandardCharsets.UTF_8);
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

        ReportRenderer.Report asked = this.renderer.asked.get(0);
        assertThat(asked.tenantId).isEqualTo(TaskContext.TENANT);
        assertThat(asked.usageKey).isEqualTo("pipeline#" + TaskContext.RUN + "#step#a1");
        assertThat(asked.title).isEqualTo("Wound assessment, run " + TaskContext.RUN);
        assertThat(asked.fields).containsEntry("Status", "DRAFT for clinician review");
        assertThat(asked.columns).containsExactly("case_id", "trend");
        assertThat(asked.rows).containsExactly(Arrays.asList("C1", "improving"), Arrays.asList("C2", "baseline"));
        assertThat(context.files).containsKey("wound-report.pdf");
        RunOutput output = context.recorded.get(0);
        assertThat(output.getName()).isEqualTo("wound-report.pdf");
        assertThat(output.getFormat()).isEqualTo("pdf");
        assertThat(output.getRows()).isEqualTo(2);
        assertThat(out == null || out.size() == 2).as("the rows pass on").isTrue();
    }

    @Test
    void aColumnTheRowsDoNotHaveIsAnError() {
        assertThatThrownBy(() -> this.task.run(this.context("columns", Arrays.asList("case_id", "stage"))))
            .hasMessage("The rows have no column 'stage'.");
    }

    @Test
    void tooManyRowsOrNoRendererFailBeforeRendering() {
        assertThatThrownBy(() -> this.task.run(this.context("maxRows", 1))).hasMessage("The report would have 2 rows; this step renders at most 1 (maxRows).");
        this.renderer.unavailable = "media-service does not render for pipelines yet";
        assertThat(this.task.unavailable()).contains("media-service does not render for pipelines yet");
        assertThat(this.renderer.asked).isEmpty();
    }

    @Test
    void withoutChosenColumnsEveryColumnIsInTheReport() throws Exception {
        TaskContext context = TaskContext.of(config("fileName", "all.pdf"), Arrays.asList(row("a", 1, "b", 2)));
        this.task.run(context);
        Map<String, byte[]> files = context.files;
        assertThat(files).containsKey("all.pdf");
        assertThat(this.renderer.asked.get(0).columns).containsExactly("a", "b");
    }
}
