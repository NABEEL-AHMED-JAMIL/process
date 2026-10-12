package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.StepResult;
import process.pipeline.backing.ReportRenderer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-346: Render PDF with sections -- several tables in one report, each under its own title and note, from an earlier
 * step's rows or the step's own input, with its own columns and maxRows; header fields fill from the first row's values.
 * Without sections the report is the one table it always was.
 */
class RenderPdfSectionsTest {

    private final RenderPdfStepTaskTest.Renderer renderer = new RenderPdfStepTaskTest.Renderer();
    private final RenderPdfStepTask task = new RenderPdfStepTask(this.renderer);

    private static Map<String, Object> section(Object... settings) {
        return config(settings);
    }

    private TaskContext context(Object... settings) {
        TaskContext context = TaskContext.of(config(settings), Collections.singletonList(row("patients", 1204L, "readmitted", 135L,
            "rate_pct", 11.2)));
        context.outputs.put("by_age", Dataset.of(Arrays.asList(row("age_band", "<40", "rate_pct", 8.1, "n", 210L),
            row("age_band", "40-64", "rate_pct", 10.9, "n", 512L), row("age_band", "65+", "rate_pct", 12.6, "n", 482L))));
        context.outputs.put("by_ward", Dataset.of(Arrays.asList(row("ward", "A", "rate_pct", 9.0), row("ward", "B", "rate_pct", 13.4))));
        return context;
    }

    @Test
    void severalTablesEachUnderItsTitleAndHeaderFieldsFromTheFirstRow() throws Exception {
        TaskContext context = this.context("fileName", "readmission", "title", "Readmission, run {{run}}",
            "fields", config("Patients", "{{patients}}", "30-day rate", "{{rate_pct}}%"),
            "sections", Arrays.asList(
                section("title", "Summary"),
                section("title", "By age band", "input", "by_age", "columns", Arrays.asList("age_band", "rate_pct"), "note", "{{patients}} patients"),
                section("title", "By ward", "input", "by_ward")));
        StepResult result = this.task.run(context);

        ReportRenderer.Report report = this.renderer.asked.get(0);
        assertThat(report.title).isEqualTo("Readmission, run " + TaskContext.RUN);
        assertThat(report.fields).containsEntry("Patients", "1204").containsEntry("30-day rate", "11.2%");
        assertThat(report.columns).isEmpty();
        assertThat(report.rows).isEmpty();
        assertThat(report.sections).extracting(s -> s.title).containsExactly("Summary", "By age band", "By ward");
        assertThat(report.sections.get(0).columns).containsExactly("patients", "readmitted", "rate_pct");
        assertThat(report.sections.get(1).columns).containsExactly("age_band", "rate_pct");
        assertThat(report.sections.get(1).rows).containsExactly(Arrays.asList("<40", 8.1), Arrays.asList("40-64", 10.9), Arrays.asList("65+", 12.6));
        assertThat(report.sections.get(1).note).isEqualTo("1204 patients");
        assertThat(report.sections.get(2).rows).hasSize(2);
        assertThat(report.orientation).isEqualTo("portrait");
        assertThat(context.files).containsKey("readmission.pdf");
        assertThat(result.getOutput()).as("the rows pass on").isNull();
        assertThat(context.lines.get(0)).startsWith("INFO Rendered 3 section(s), 6 row(s) in all, as readmission.pdf")
            .contains("Summary: 1 row(s); By age band: 3 row(s); By ward: 2 row(s)");
    }

    @Test
    void aSectionHeldToItsOwnMaxRowsAndColumns() {
        assertThatThrownBy(() -> this.task.run(this.context("fileName", "r", "sections",
            Collections.singletonList(section("title", "Ages", "input", "by_age", "maxRows", 2)))))
            .hasMessage("Section 1 (<by_age>) would have 3 rows; this step renders at most 2 (maxRows).");
        assertThatThrownBy(() -> this.task.run(this.context("fileName", "r", "sections",
            Collections.singletonList(section("title", "Wards", "input", "by_ward", "columns", Collections.singletonList("beds"))))))
            .hasMessage("Section 1 (<by_ward>): the rows have no column 'beds'.");
    }

    @Test
    void withoutSectionsTheReportIsTheOneTableItWas() throws Exception {
        this.task.run(this.context("fileName", "r", "title", "{{patients}} patients"));
        ReportRenderer.Report report = this.renderer.asked.get(0);
        assertThat(report.sections).isEmpty();
        assertThat(report.columns).containsExactly("patients", "readmitted", "rate_pct");
        assertThat(report.title).isEqualTo("1204 patients");
    }
}
