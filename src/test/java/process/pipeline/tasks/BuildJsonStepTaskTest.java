package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.data.Values;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-451: Build JSON shapes rows into nested JSON -- keys nested by their dots, lists grouped from many rows, fixed values
 * and formulas, types kept or made, empty keys said, a too-big document refused, and the documents in a column, as columns
 * or in a file.
 */
class BuildJsonStepTaskTest {

    private final BuildJsonStepTask task = new BuildJsonStepTask();

    /** One row per finding, as a referral's findings come (synthetic). */
    private final List<Map<String, Object>> findings = Arrays.asList(
        row("patient_id", "P-101", "patient_name", "Ada Test", "code", "L89.1", "text", "Stage 1 pressure injury", "seen", "2026-10-30",
            "acuity", "7", "urgent", "yes"),
        row("patient_id", "P-101", "patient_name", "Ada Test", "code", "E11.9", "text", "Type 2 diabetes", "seen", "2026-10-30",
            "acuity", "7", "urgent", "yes"),
        row("patient_id", "P-102", "patient_name", "Bo Sample", "code", "I10", "text", "Hypertension", "seen", "11/1/2026",
            "acuity", "", "urgent", "no"));

    private static Map<String, Object> key(String key, Object... more) {
        Object[] all = new Object[more.length + 2];
        all[0] = "key";
        all[1] = key;
        System.arraycopy(more, 0, all, 2, more.length);
        return config(all);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keys(Object... keys) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Object key : keys) {
            all.add((Map<String, Object>) key);
        }
        return all;
    }

    private StepResult run(Map<String, Object> config) throws Exception {
        return this.task.run(TaskContext.of(config, this.findings));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> doc(Map<String, Object> row, String column) {
        return (Map<String, Object>) row.get(column);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onePerGroupNestsObjectsAndListsTheGroupsRowsWithAFixedValue() throws Exception {
        StepResult result = this.run(config("mode", "group", "groupBy", Collections.singletonList("patient_id"), "fields", keys(
            key("patient.id", "column", "patient_id"),
            key("patient.name", "column", "patient_name"),
            key("findings", "from", "rows", "fields", keys(key("code", "column", "code"), key("text", "column", "text"))),
            key("codes", "from", "list", "column", "code"),
            key("source", "from", "value", "value", "etl-console"))));

        List<Map<String, Object>> rows = result.getOutput().getRows();
        assertThat(result.getOutput().getColumns()).containsExactly("patient_id", "json");
        assertThat(rows).hasSize(2);
        assertThat(Values.JSON.writeValueAsString(doc(rows.get(0), "json"))).isEqualTo("{\"patient\":{\"id\":\"P-101\",\"name\":\"Ada Test\"},"
            + "\"findings\":[{\"code\":\"L89.1\",\"text\":\"Stage 1 pressure injury\"},{\"code\":\"E11.9\",\"text\":\"Type 2 diabetes\"}],"
            + "\"codes\":[\"L89.1\",\"E11.9\"],\"source\":\"etl-console\"}");
        assertThat((List<Object>) doc(rows.get(1), "json").get("findings")).hasSize(1);
        assertThat(rows.get(1).get("patient_id")).isEqualTo("P-102");
        assertThat(result.getNote()).isEqualTo("2 documents");
    }

    @Test
    void typesAreKeptOrMadeAndAnEmptyKeyIsNullAndSaid() throws Exception {
        TaskContext context = TaskContext.of(config("fields", keys(
            key("id", "column", "patient_id"),
            key("acuity", "column", "acuity", "type", "number"),
            key("urgent", "column", "urgent", "type", "boolean"),
            key("seen", "column", "seen", "type", "date"),
            key("label", "from", "expression", "expression", "concat(patient_id, ' / ', code)"),
            key("missing", "column", "nothing_here"))), this.findings);
        List<Map<String, Object>> rows = this.task.run(context).getOutput().getRows();

        assertThat(rows.get(0)).containsKeys("patient_id", "code", "json");
        Map<String, Object> first = doc(rows.get(0), "json");
        assertThat(first.get("acuity")).isEqualTo(7L);
        assertThat(first.get("urgent")).isEqualTo(true);
        assertThat(first.get("seen")).isEqualTo("2026-10-30");
        assertThat(first.get("label")).isEqualTo("P-101 / L89.1");
        assertThat(first).containsEntry("missing", null);
        Map<String, Object> third = doc(rows.get(2), "json");
        assertThat(third.get("seen")).as("a US date as ISO").isEqualTo("2026-11-01");
        assertThat(third.get("acuity")).as("empty is null, not 0").isNull();
        assertThat(third.get("urgent")).isEqualTo(false);
        assertThat(context.lines).contains("WARN No value for acuity in 1 document; missing in every document.");
    }

    @Test
    void leaveOutEmptyKeysAndAJsonTextBecomesItsList() throws Exception {
        TaskContext context = TaskContext.of(config("skipEmpty", true, "fields", keys(
            key("id", "column", "id"), key("tags", "column", "tags"), key("note", "column", "note"))),
            Arrays.asList(row("id", 1, "tags", "[\"wound\", \"follow-up\"]", "note", null)));
        Map<String, Object> made = doc(this.task.run(context).getOutput().getRows().get(0), "json");
        assertThat(made).containsOnlyKeys("id", "tags");
        assertThat(made.get("tags")).isEqualTo(Arrays.asList("wound", "follow-up"));
        assertThat(made.get("id")).as("a number stays a number").isEqualTo(1);
    }

    @Test
    void oneDocumentForTheRunAsItsOwnColumnsWithDistinctLists() throws Exception {
        List<Map<String, Object>> rows = this.run(config("mode", "all", "output", "rows", "fields", keys(
            key("source", "from", "value", "value", "etl-console"),
            key("patients", "from", "list", "column", "patient_id", "distinct", true),
            key("count.findings", "from", "value", "value", 3)))).getOutput().getRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("source", "patients", "count");
        assertThat(rows.get(0).get("patients")).isEqualTo(Arrays.asList("P-101", "P-102"));
        assertThat(rows.get(0).get("count")).isEqualTo(Collections.singletonMap("findings", 3));
    }

    @Test
    void aFileKeepsTheDocumentsAsJsonLinesAndTheRowsGoOn() throws Exception {
        TaskContext context = TaskContext.of(config("mode", "group", "groupBy", Collections.singletonList("patient_id"), "output", "file",
            "fileName", "referrals.jsonl", "format", "jsonl", "fields", keys(key("id", "column", "patient_id"),
                key("codes", "from", "list", "column", "code"))), this.findings);
        StepResult result = this.task.run(context);
        assertThat(result.getOutput().getRows()).hasSize(2);
        assertThat(new String(context.files.get("referrals.jsonl"), StandardCharsets.UTF_8))
            .isEqualTo("{\"id\":\"P-101\",\"codes\":[\"L89.1\",\"E11.9\"]}\n{\"id\":\"P-102\",\"codes\":[\"I10\"]}\n");
        assertThat(context.recorded).extracting(RunOutput::getName).containsExactly("referrals.jsonl");
    }

    @Test
    void aDocumentBiggerThanLargestDocumentFailsSayingWhich() {
        assertThatThrownBy(() -> this.run(config("mode", "group", "groupBy", Collections.singletonList("patient_id"), "maxKb", 1,
            "fields", keys(key("text", "from", "expression", "expression", "concat(text, text, text, text, text, text, text, text, text, text, "
                + "text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, "
                + "text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text, text)")))))
            .hasMessageContaining("The document for patient_id P-101 is 2 KB; a document is at most 1 KB (Largest document)");
    }

    @Test
    void theCheckSaysWhatAShapeLacksOrWhereItsKeysClash() {
        assertThat(this.task.check(config("mode", "group", "output", "file", "fields", keys(
            key("patient", "column", "patient_id"),
            key("patient.id", "column", "patient_id"),
            key("findings", "from", "rows"),
            key("label", "from", "expression", "expression", "concat(a,"),
            key("source", "from", "value"),
            key("codes", "from", "list")))))
            .extracting(p -> p.getPath() + " " + p.getMessage())
            .contains("groupBy One document per group needs the columns that make a group",
                "fileName A file needs its name, e.g. referrals-{{date}}.json",
                "fields[1].key 'patient' is a value, so 'patient.id' cannot nest in it",
                "fields[2].fields 'findings' is a list of objects: give each object's keys",
                "fields[4].value 'source' is a fixed value: give it (null for none)",
                "fields[5].column 'codes' takes a column's value: name the column");
        assertThat(this.task.check(config("fields", keys(key("a.b", "column", "x"), key("a", "column", "y")))))
            .extracting(p -> p.getMessage()).containsExactly("'a' already holds 'a.b', so it cannot be a value too");
        assertThat(this.task.check(config("fields", keys(key("patient.id", "column", "x"), key("patient.name", "column", "y"))))).isEmpty();
    }
}
