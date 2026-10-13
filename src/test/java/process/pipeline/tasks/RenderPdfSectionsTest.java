package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.StepResult;

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

        RenderPdfStepTaskTest.Asked report = this.renderer.asked.get(0);
        assertThat(report.head.path("title").asText()).isEqualTo("Readmission, run " + TaskContext.RUN);
        assertThat(report.head.path("fields").path("Patients").asText()).isEqualTo("1204");
        assertThat(report.head.path("fields").path("30-day rate").asText()).isEqualTo("11.2%");
        assertThat(report.tables).extracting(t -> t.title).containsExactly("Summary", "By age band", "By ward");
        assertThat(report.table(0).columns).containsExactly("patients", "readmitted", "rate_pct");
        assertThat(report.table(1).columns).containsExactly("age_band", "rate_pct");
        assertThat(report.table(1).rows).containsExactly(Arrays.<Object>asList("<40", 8.1), Arrays.<Object>asList("40-64", 10.9),
            Arrays.<Object>asList("65+", 12.6));
        assertThat(report.table(1).note).isEqualTo("1204 patients");
        assertThat(report.table(2).rows).hasSize(2);
        assertThat(report.head.path("orientation").asText()).isEqualTo("portrait");
        assertThat(context.files).containsOnlyKeys("readmission.pdf");
        assertThat(result.getOutput()).as("the rows pass on").isNull();
        assertThat(context.lines.get(0)).startsWith("INFO Rendered 3 sections as readmission.pdf: 2 page(s)")
            .contains("Summary: 1 of 1 row(s); By age band: 3 of 3 row(s); By ward: 2 of 2 row(s)");
    }

    @Test
    void aSectionPrintsItsOwnMaxRowsAndKeepsTheFullDataBesideTheReport() throws Exception {
        TaskContext context = this.context("fileName", "r", "sections",
            Collections.singletonList(section("title", "Ages", "input", "by_age", "maxRows", 2)));
        this.task.run(context);

        RenderPdfStepTaskTest.Table ages = this.renderer.asked.get(0).table(0);
        assertThat(ages.rows).hasSize(2);
        assertThat(ages.trailer).isEqualTo("Showing the first 2 of 3 rows. 1 more row: the full data is in r-by_age-data.csv, kept with this report.");
        assertThat(context.files).containsOnlyKeys("r.pdf", "r-by_age-data.csv");
        assertThat(new String(context.files.get("r-by_age-data.csv"))).isEqualTo("age_band,rate_pct,n\r\n<40,8.1,210\r\n40-64,10.9,512\r\n65+,12.6,482\r\n");

        assertThatThrownBy(() -> this.task.run(this.context("fileName", "r", "sections",
            Collections.singletonList(section("title", "Wards", "input", "by_ward", "columns", Collections.singletonList("beds"))))))
            .hasMessage("Section 1 (<by_ward>): the rows have no column 'beds'.");
    }

    @Test
    void withoutSectionsTheReportIsTheOneTableItWas() throws Exception {
        this.task.run(this.context("fileName", "r", "title", "{{patients}} patients"));
        RenderPdfStepTaskTest.Asked report = this.renderer.asked.get(0);
        assertThat(report.tables).hasSize(1);
        assertThat(report.table(0).title).isNull();
        assertThat(report.table(0).columns).containsExactly("patients", "readmitted", "rate_pct");
        assertThat(report.head.path("title").asText()).isEqualTo("1204 patients");
    }
}
