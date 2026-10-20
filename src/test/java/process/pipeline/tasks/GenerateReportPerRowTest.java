package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.tasks.GenerateReportStepTaskTest.DESIGN;
import static process.pipeline.tasks.GenerateReportStepTaskTest.JSON;
import static process.pipeline.tasks.GenerateReportStepTaskTest.config;

/**
 * "One report per row": each row is its own report -- its own job and idempotency key (the run's step, attempt and row),
 * its name from the row's columns, its data the row as one record (a.b columns nested, JSON columns as lists), its
 * lineage the run and the row -- and each PDF is kept with the run under a name of its own. The default stays one report.
 */
class GenerateReportPerRowTest {

    @TempDir
    Path dir;

    private static Dataset calls() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String f : new String[] {"a.m4a", "b.m4a"}) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("summary", "summary of " + f);
            row.put("keyPoints", "[\"one\",\"two\"]");
            row.put("call.file", f);
            row.put("status.risk", "Low");
            rows.add(row);
        }
        return Dataset.of(rows);
    }

    @Test
    void eachRowIsAReportOfItsOwnWithTheRowAsItsRecord() throws Exception {
        GenerateReportStepTaskTest.FakeStudio studio = new GenerateReportStepTaskTest.FakeStudio();
        StreamTry t = new StreamTry(config("templateId", DESIGN, "perRow", true, "reportName", "Call · {{call.file}}",
            "jsonColumns", Arrays.asList("keyPoints")), calls(), 64L << 20, this.dir, 2);

        assertThat(new GenerateReportStepTask(studio, d -> { }).stream(t).getRecordsOut()).isEqualTo(2);

        assertThat(studio.sent).hasSize(4);
        JsonNode head = JSON.readTree(studio.sent.get(2)).path("head");
        assertThat(head.path("name").asText()).isEqualTo("Call · b.m4a");
        assertThat(head.path("rootPath").asText()).isEqualTo("$[0]");
        assertThat(head.path("pipeline").path("row").asInt()).isEqualTo(2);
        assertThat(head.path("reason").asText()).endsWith(", row 2");
        JsonNode record = JSON.readTree(studio.sent.get(3));
        assertThat(record.path("call").path("file").asText()).isEqualTo("b.m4a");
        assertThat(record.path("status").path("risk").asText()).isEqualTo("Low");
        assertThat(record.path("keyPoints").isArray()).isTrue();
        assertThat(record.has("call.file")).isFalse();
        assertThat(studio.key).isEqualTo("pipeline#2#step#a1#r2");
        assertThat(t.files.keySet()).containsExactly("Wound_clinic_weekly_run_2.pdf", "Wound_clinic_weekly_run_2-2.pdf");
        assertThat(t.recorded).hasSize(2);
        assertThat(t.lines).anyMatch(l -> l.contains("2 report(s) made from 2 row(s), one a row."));
    }

    @Test
    void moreRowsThanMaxReportsFailBeforeAnyReport() {
        GenerateReportStepTaskTest.FakeStudio studio = new GenerateReportStepTaskTest.FakeStudio();
        StreamTry t = new StreamTry(config("templateId", DESIGN, "perRow", true, "maxReports", 1), calls(), 64L << 20, this.dir, 2);
        assertThatThrownBy(() -> new GenerateReportStepTask(studio, d -> { }).stream(t)).hasMessageContaining("at most 1 reports (maxReports)");
        assertThat(studio.sent).isEmpty();
    }

    @Test
    void aDottedNameStaysFlatWhenItsFirstPartIsAColumnOfItsOwn() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("call", "x");
        row.put("call.file", "a");
        row.put("a.b.c", 1);
        Map<String, Object> out = GenerateReportStepTask.nested(row);
        assertThat(out).containsEntry("call", "x").containsEntry("call.file", "a");
        assertThat(out.get("a")).isEqualTo(Map.of("b", Map.of("c", 1)));
    }
}
