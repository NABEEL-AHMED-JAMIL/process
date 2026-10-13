package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.RunOutput;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * ADR-027: render_pdf with big data, as the engine streams it. A table prints its first maxRows rows and says how many
 * more there are; the full data goes to a CSV kept beside the PDF -- written in the same pass that reads the first rows,
 * so the input is read once -- and recorded as a run output of its own. Without attachFullData only the printed rows are
 * read. A statistics section covers every row, streamed, and its numbers are exact. The step holds a batch, not the
 * table, and leaves nothing in its scratch directory.
 */
class RenderPdfBigDataTest {

    @TempDir
    Path dir;

    private final RenderPdfStepTaskTest.Renderer renderer = new RenderPdfStepTaskTest.Renderer();
    private final RenderPdfStepTask task = new RenderPdfStepTask(this.renderer);

    private static Dataset encounters(int n) {
        List<Map<String, Object>> rows = new ArrayList<>(n);
        String[] departments = {"Cardiology", "Oncology", "Emergency", "Wound Care"};
        for (int i = 0; i < n; i++) {
            rows.add(row("patient_id", String.format("P%06d", i), "department", departments[i % departments.length],
                "los_days", (long) (i % 31), "charge_usd", i % 10 == 0 ? "" : (i % 997) + ".25"));
        }
        return Dataset.of(rows);
    }

    @Test
    void aBigTablePrintsItsFirstRowsAndKeepsTheFullDataBesideThePdfReadingTheInputOnce() throws Exception {
        StreamTry streamed = new StreamTry(config("fileName", "encounters", "maxRows", 1000), encounters(20_000), 64L << 20, this.dir, 1024);

        this.task.stream(streamed);

        RenderPdfStepTaskTest.Table table = this.renderer.asked.get(0).table(0);
        assertThat(table.rows).hasSize(1000);
        assertThat(table.rows.get(999).get(0)).isEqualTo("P000999");
        assertThat(table.trailer).isEqualTo("Showing the first 1,000 of 20,000 rows. 19,000 more rows: the full data is in "
            + "encounters-data.csv, kept with this report.");
        assertThat(streamed.files).containsOnlyKeys("encounters-data.csv", "encounters.pdf");
        String csv = new String(streamed.files.get("encounters-data.csv"), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(20_001).startsWith("patient_id,department,los_days,charge_usd", "P000000,Cardiology,0,");
        assertThat(streamed.rowsRead).as("one pass: the CSV and the first rows together").isEqualTo(20_000);
        RunOutput pdf = streamed.recorded.get(0);
        RunOutput data = streamed.recorded.get(1);
        assertThat(pdf.getName()).isEqualTo("encounters.pdf");
        assertThat(pdf.getRows()).isEqualTo(1000);
        assertThat(pdf.getPages()).isEqualTo(2);
        assertThat(data.getName()).isEqualTo("encounters-data.csv");
        assertThat(data.getFormat()).isEqualTo("csv");
        assertThat(data.getRows()).isEqualTo(20_000);
        assertThat(data.getBytes()).isEqualTo(streamed.files.get("encounters-data.csv").length);
        assertThat(streamed.scratchFiles()).as("the report's lines go with the report").isZero();
        assertThat(streamed.memory.used()).as("the run's budget is given back").isZero();
    }

    @Test
    void withoutTheFullDataOnlyThePrintedRowsAreRead() throws Exception {
        StreamTry streamed = new StreamTry(config("fileName", "encounters", "maxRows", 100, "attachFullData", false), encounters(50_000),
            64L << 20, this.dir, 1024);

        this.task.stream(streamed);

        assertThat(streamed.files).containsOnlyKeys("encounters.pdf");
        assertThat(this.renderer.asked.get(0).table(0).trailer)
            .isEqualTo("Showing the first 100 of 50,000 rows. 49,900 more rows: the full data is the step's input in this run.");
        assertThat(streamed.rowsRead).as("a batch, not the input").isLessThanOrEqualTo(1024);
    }

    @Test
    void twoTablesOfOneInputShareOneDataFile() throws Exception {
        StreamTry streamed = new StreamTry(config("fileName", "r", "sections", Arrays.asList(
            config("title", "First", "maxRows", 10), config("title", "Narrow", "columns", Collections.singletonList("patient_id"), "maxRows", 5))),
            encounters(100), 64L << 20, this.dir, 64);

        this.task.stream(streamed);

        assertThat(streamed.files).containsOnlyKeys("r-input-data.csv", "r.pdf");
        assertThat(this.renderer.asked.get(0).table(1).trailer).contains("r-input-data.csv");
    }

    @Test
    void aStatisticsSectionCoversEveryRowWithExactNumbers() throws Exception {
        int n = 30_000;
        StreamTry streamed = new StreamTry(config("fileName", "stats", "sections", Arrays.asList(
            config("title", "Profile", "kind", "statistics", "columns", Arrays.asList("department", "los_days", "charge_usd"),
                "histogram", Collections.singletonList("los_days")))), encounters(n), 64L << 20, this.dir, 1024);

        this.task.stream(streamed);

        RenderPdfStepTaskTest.Table profile = this.renderer.asked.get(0).table(0);
        assertThat(profile.columns).isEqualTo(ReportStatistics.HEADER);
        assertThat(profile.note).isEqualTo("Every one of the 30,000 rows, column by column.");
        List<Object> department = profile.rows.get(0);
        assertThat(department.subList(0, 4)).containsExactly("department", n, 0, 4);
        assertThat(department.get(4)).isEqualTo("Cardiology");
        assertThat(department.get(5)).isEqualTo("Wound Care");
        assertThat(department.get(6)).as("text has no sum").isNull();
        assertThat(department.get(8)).isEqualTo("Cardiology (7,500); Emergency (7,500); Oncology (7,500); Wound Care (7,500)");

        // The exact answer, worked out the slow way.
        long losSum = 0;
        BigDecimal chargeSum = BigDecimal.ZERO;
        long chargeValues = 0;
        BigDecimal chargeMin = null;
        BigDecimal chargeMax = null;
        for (int i = 0; i < n; i++) {
            losSum += i % 31;
            if (i % 10 != 0) {
                BigDecimal charge = new BigDecimal((i % 997) + ".25");
                chargeSum = chargeSum.add(charge);
                chargeValues++;
                chargeMin = chargeMin == null || charge.compareTo(chargeMin) < 0 ? charge : chargeMin;
                chargeMax = chargeMax == null || charge.compareTo(chargeMax) > 0 ? charge : chargeMax;
            }
        }
        List<Object> los = profile.rows.get(1);
        assertThat(los.subList(0, 4)).containsExactly("los_days", n, 0, 31);
        assertThat(new BigDecimal(los.get(4).toString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(los.get(5).toString())).isEqualByComparingTo("30");
        assertThat(new BigDecimal(los.get(6).toString())).isEqualByComparingTo(BigDecimal.valueOf(losSum));
        assertThat(new BigDecimal(los.get(7).toString())).isEqualByComparingTo(ReportStatistics.mean(BigDecimal.valueOf(losSum), n));
        List<Object> charge = profile.rows.get(2);
        assertThat(charge.subList(0, 3)).containsExactly("charge_usd", n, n / 10);
        assertThat(new BigDecimal(charge.get(4).toString())).isEqualByComparingTo(chargeMin);
        assertThat(new BigDecimal(charge.get(5).toString())).isEqualByComparingTo(chargeMax);
        assertThat(new BigDecimal(charge.get(6).toString())).isEqualByComparingTo(chargeSum);
        assertThat(new BigDecimal(charge.get(7).toString())).isEqualByComparingTo(ReportStatistics.mean(chargeSum, chargeValues));

        RenderPdfStepTaskTest.Table histogram = this.renderer.asked.get(0).table(1);
        assertThat(histogram.title).isEqualTo("Profile: los_days");
        long binned = 0;
        for (List<Object> bin : histogram.rows) {
            binned += ((Number) bin.get(2)).longValue();
        }
        assertThat(binned).as("every value in one bin").isEqualTo(n);
        assertThat(streamed.files).as("statistics keep no data file").containsOnlyKeys("stats.pdf");
        assertThat(streamed.memory.used()).isZero();
    }

    @Test
    void aStatisticsSectionOfManyDistinctValuesEstimatesThemWithinItsBudget() throws Exception {
        int n = 60_000;
        StreamTry tight = new StreamTry(config("fileName", "ids", "sections", Collections.singletonList(
            config("title", "Ids", "kind", "statistics", "columns", Collections.singletonList("patient_id")))), encounters(n), 4L << 20,
            this.dir, 1024);

        this.task.stream(tight);

        List<Object> ids = this.renderer.asked.get(0).table(0).rows.get(0);
        String distinct = ids.get(3).toString();
        assertThat(distinct).startsWith("~").endsWith("(estimate)");
        long estimate = Long.parseLong(distinct.replaceAll("[^0-9]", ""));
        assertThat((double) Math.abs(estimate - n) / n).as("HyperLogLog's error").isLessThan(0.03);
        assertThat(ids.get(4)).isEqualTo("P000000");
        assertThat(ids.get(5)).isEqualTo("P059999");
        assertThat(tight.memory.used()).isZero();
    }
}
