package process.customer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-426: a run with no report, as a document -- its (masked) result rows as a Markdown table or an HTML page. */
class RowDocumentsTest {

    static Map<String, Object> result(List<Map<String, Object>> rows, long rowCount, List<String> masked) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("step", "triage");
        r.put("columns", Arrays.asList("patient", "flag", "note", "meds"));
        r.put("rowCount", rowCount);
        r.put("rows", rows);
        r.put("truncated", rows.size() < rowCount);
        r.put("masked", masked);
        r.put("problem", null);
        return r;
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    static List<Map<String, Object>> rows() {
        return Arrays.asList(row("patient", "•••••0103", "flag", "High", "note", "a | b\nnext *line*", "meds", Arrays.asList("Warfarin", "Metformin")),
            row("patient", "•••••0104", "flag", null, "note", "<script>x</script>", "meds", Collections.singletonMap("n", 2.50)));
    }

    @Test
    void theRowsAreAMarkdownTableThatSaysWhatTheyAre() {
        String md = new String(RowDocuments.markdown("2985", result(rows(), 2, Arrays.asList("patient"))), StandardCharsets.UTF_8);

        assertThat(md).isEqualTo(String.join("\n",
            "From the result of run 2985 (no report).",
            "",
            "> The output of step triage: 2 rows; masked by the workspace's data policy: patient. Not signed: ask the pipeline for a report "
                + "for a signed PDF.",
            "",
            "| patient | flag | note | meds |",
            "| --- | --- | --- | --- |",
            "| •••••0103 | High | a \\| b<br>next \\*line\\* | \\[\"Warfarin\",\"Metformin\"\\] |",
            "| •••••0104 |  | \\<script>x\\</script> | {\"n\":2.5} |",
            ""));
    }

    @Test
    void aTruncatedResultSaysHowManyOfHowMany() {
        String md = new String(RowDocuments.markdown("7", result(rows(), 1234, Collections.<String>emptyList())), StandardCharsets.UTF_8);
        assertThat(md).contains("> The output of step triage: the first 2 of 1,234 rows. Not signed");
    }

    @Test
    void noRowsOrAProblemIsSaidInWords() {
        assertThat(new String(RowDocuments.markdown("7", result(Collections.<Map<String, Object>>emptyList(), 0, Collections.<String>emptyList())),
            StandardCharsets.UTF_8)).endsWith("\n\n_No rows._\n");
        Map<String, Object> expired = result(Collections.<Map<String, Object>>emptyList(), 0, Collections.<String>emptyList());
        expired.put("problem", "The run's output is past its retention.");
        assertThat(new String(RowDocuments.markdown("7", expired), StandardCharsets.UTF_8)).endsWith("_The run's output is past its retention._\n");
    }

    @Test
    void theHtmlPageEscapesEveryValueAndRunsNothing() {
        String html = new String(RowDocuments.html("2985", result(rows(), 2, Arrays.asList("patient"))), StandardCharsets.UTF_8);

        assertThat(html).startsWith("<!DOCTYPE html>").contains("default-src 'none'").contains("<title>Result of run 2985</title>")
            .contains("<th>patient</th><th>flag</th><th>note</th><th>meds</th>").contains("<td>a | b\nnext *line*</td>")
            .contains("<td>&lt;script&gt;x&lt;/script&gt;</td>").contains("<td>[&quot;Warfarin&quot;,&quot;Metformin&quot;]</td>")
            .contains("masked by the workspace&#39;s data policy: patient").doesNotContain("<script>");
    }
}
