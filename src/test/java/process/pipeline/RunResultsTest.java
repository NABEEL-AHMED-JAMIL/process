package process.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import process.pipeline.data.RowSource;
import process.pipeline.items.ItemMasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MIG-424: a finished run's answer -- from its final output or a named step, chosen fields, taken, capped and masked. */
class RunResultsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long RUN = 7001L;

    private final StepStore steps = mock(StepStore.class);
    private final DatasetStore datasets = mock(DatasetStore.class);
    private final RunResults results = new RunResults(this.steps, this.datasets);
    private final Map<Long, StepStore.KeptOutput> kept = new HashMap<>();

    private StepStore.StepRow step(long id, int index, String key, String status, int attempt) {
        StepStore.StepRow row = new StepStore.StepRow();
        row.stepExecutionId = id;
        row.jobQueueId = RUN;
        row.stepIndex = index;
        row.stepKey = key;
        row.status = status;
        row.attempt = attempt;
        return row;
    }

    private void output(long stepExecutionId, Dataset dataset) throws Exception {
        String key = "runs/" + RUN + "/" + stepExecutionId;
        this.kept.put(stepExecutionId, new StepStore.KeptOutput(stepExecutionId, key, (long) dataset.size(),
            Instant.now().plusSeconds(3600), false));
        when(this.datasets.open(eq(key), anyInt())).thenAnswer(call -> RowSource.of(dataset, 2));
    }

    @SuppressWarnings("unchecked")
    private void wire(StepStore.StepRow... rows) {
        when(this.steps.stepsOfRun(RUN)).thenReturn(Arrays.asList(rows));
        when(this.steps.keptOutputsOf(any())).thenAnswer(call -> {
            Map<Long, StepStore.KeptOutput> out = new HashMap<>();
            for (Long id : (Collection<Long>) call.getArgument(0)) {
                if (this.kept.containsKey(id)) {
                    out.put(id, this.kept.get(id));
                }
            }
            return out;
        });
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static RunResults.Selection selection(String json) throws Exception {
        List<String> problems = new ArrayList<>();
        RunResults.Selection s = RunResults.Selection.of(JSON.readTree(json), problems);
        assertThat(problems).isEmpty();
        return s;
    }

    private void careplan() throws Exception {
        Dataset read = new Dataset(Arrays.asList("mrn"), Arrays.asList(row("mrn", "MRN-1")));
        Dataset scored = new Dataset(Arrays.asList("mrn", "risk", "eligible", "summary", "score"), Arrays.asList(
            row("mrn", "MRN-1", "risk", "High", "eligible", true, "summary", "{\"age\": 71, \"plan\": {\"tier\": \"gold\"}}", "score", 8),
            row("mrn", "MRN-2", "risk", "Low", "eligible", false, "summary", "{\"age\": 40}", "score", 3),
            row("mrn", "MRN-3", "risk", "Low", "eligible", true, "summary", "{}", "score", "n/a")));
        output(11L, read);
        output(12L, scored);
        // An attempt 1 whose output must never be read, and a notify step after the last output that kept none.
        output(1L, new Dataset(Arrays.asList("risk"), Arrays.asList(row("risk", "stale"))));
        wire(step(1L, 0, "score", "Completed", 1), step(11L, 0, "read", "Completed", 2), step(12L, 1, "score", "Completed", 2),
            step(13L, 2, "tell", "Completed", 2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFinalOutputIsTheLastStepOfTheLatestAttemptThatKeptOne() throws Exception {
        careplan();
        RunResults.Result r = this.results.read(RUN, selection("{\"fields\": [\"risk\", \"eligible\", {\"path\": \"summary.plan.tier\", "
            + "\"as\": \"tier\"}, \"summary\"]}"), null);
        assertThat(r.problem).isNull();
        assertThat(r.step).isEqualTo("score");
        assertThat(r.rowCount).isEqualTo(3);
        assertThat(r.values).containsEntry("risk", "High").containsEntry("eligible", true).containsEntry("tier", "gold");
        // A column of JSON text is given as that JSON, so a condition can read summary.age.
        assertThat((Map<String, Object>) r.values.get("summary")).containsEntry("age", 71);
        assertThat(r.truncated).isFalse();
    }

    @Test
    void aNamedStepAnswersItsOwnOutput() throws Exception {
        careplan();
        RunResults.Result r = this.results.read(RUN, selection("{\"from\": \"read\", \"take\": \"all\"}"), null);
        assertThat(r.step).isEqualTo("read");
        assertThat(r.rows).containsExactly(row("mrn", "MRN-1"));
        RunResults.Result none = this.results.read(RUN, selection("{\"from\": \"nope\"}"), null);
        assertThat(none.problem).isEqualTo("The run has no step nope.");
        RunResults.Result quiet = this.results.read(RUN, selection("{\"from\": \"tell\"}"), null);
        assertThat(quiet.problem).isEqualTo("The step tell kept no output of its own.");
    }

    @Test
    void countAndAggregatesReadEveryRowAndLeaveOutWhatIsNoNumber() throws Exception {
        careplan();
        assertThat(this.results.read(RUN, selection("{\"take\": \"count\"}"), null).values).containsEntry("count", 3L);
        assertThat(this.results.read(RUN, selection("{\"take\": \"sum\", \"fields\": [\"score\"]}"), null).values).containsEntry("score", 11L);
        assertThat(this.results.read(RUN, selection("{\"take\": \"max\", \"fields\": [\"score\"]}"), null).values).containsEntry("score", 8L);
        assertThat(this.results.read(RUN, selection("{\"take\": \"avg\", \"fields\": [\"score\"]}"), null).values).containsEntry("score", 5.5);
        assertThat(this.results.read(RUN, selection("{\"take\": \"min\", \"fields\": [\"summary.age\"]}"), null).values)
            .containsEntry("age", 40L);
    }

    @Test
    void allIsCutAtMaxRowsAndSaysSo() throws Exception {
        careplan();
        RunResults.Result r = this.results.read(RUN, selection("{\"take\": \"all\", \"maxRows\": 2, \"fields\": [\"mrn\"]}"), null);
        assertThat(r.rows).containsExactly(row("mrn", "MRN-1"), row("mrn", "MRN-2"));
        assertThat(r.truncated).isTrue();
    }

    @Test
    void anAnswerBiggerThan16KbOfJsonIsCut() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            big.append("abcdefghij");
        }
        for (int i = 0; i < 40; i++) {
            rows.add(row("note", big.toString()));
        }
        output(21L, new Dataset(Arrays.asList("note"), rows));
        wire(step(21L, 0, "notes", "Completed", 1));
        RunResults.Result r = this.results.read(RUN, selection("{\"take\": \"all\", \"maxRows\": 40}"), null);
        assertThat(RunResults.size(r.rows)).isLessThanOrEqualTo(RunResults.MAX_JSON_CHARS);
        assertThat(r.rows.size()).isLessThan(40);
        assertThat(r.truncated).isTrue();
    }

    @Test
    void theRowsAreMaskedBeforeAnythingIsTakenFromThem() throws Exception {
        Dataset patients = new Dataset(Arrays.asList("patient_name", "email_note", "risk", "card"), Arrays.asList(
            row("patient_name", "Jane Synthetic", "email_note", "write to jane@example.test", "risk", "High",
                "card", "{\"mrn\": \"MRN-778812\", \"risk\": \"High\"}")));
        output(31L, patients);
        wire(step(31L, 0, "careplan", "Completed", 1));
        JsonNode assets = JSON.readTree("[{\"columns\": {\"patient_name\": \"person_name\", \"mrn\": \"medical_record_number\"}, "
            + "\"kinds\": [\"email\"]}]");
        RunResults.Result r = this.results.read(RUN, selection("{\"fields\": [\"patient_name\", \"email_note\", \"risk\", \"card\", "
            + "{\"path\": \"card.mrn\", \"as\": \"mrn\"}]}"), ItemMasks.forWorkspace(assets, false));
        assertThat(r.values.get("patient_name")).isNotEqualTo("Jane Synthetic");
        assertThat(String.valueOf(r.values.get("email_note"))).doesNotContain("jane@example.test");
        assertThat(r.values).containsEntry("risk", "High");
        assertThat(String.valueOf(r.values.get("mrn"))).doesNotContain("778812");
        assertThat(String.valueOf(r.values.get("card"))).doesNotContain("778812");
        assertThat(r.masked).contains("patient_name", "email_note", "card");

        // Unreadable masks (analytics away): fail closed -- every pattern kind is masked everywhere.
        RunResults.Result closed = this.results.read(RUN, selection("{\"fields\": [\"email_note\", \"risk\"]}"), ItemMasks.forWorkspace(null, false));
        assertThat(String.valueOf(closed.values.get("email_note"))).doesNotContain("jane@example.test");
    }

    /** MIG-424: a sensitive pipeline's one patient: one value says enough for these masks, the person's name is hidden. */
    @Test
    void aSensitivePipelinesOnePatientIsMaskedFromOneRow() throws Exception {
        output(51L, new Dataset(Arrays.asList("patient_name", "risk"), Arrays.asList(row("patient_name", "Pat Synthetic", "risk", "High"))));
        wire(step(51L, 0, "decide", "Completed", 1));
        RunResults.Result r = this.results.read(RUN, selection("{\"fields\": [\"patient_name\", \"risk\"]}"),
            ItemMasks.forWorkspace(JSON.readTree("[]"), true));
        assertThat(r.values.get("patient_name")).isNotEqualTo("Pat Synthetic");
        assertThat(r.values).containsEntry("risk", "High");
        assertThat(r.masked).containsExactly("patient_name");
        // Not marked sensitive, and no asset tags it: shown.
        assertThat(this.results.read(RUN, selection("{\"fields\": [\"patient_name\"]}"), ItemMasks.forWorkspace(JSON.readTree("[]"), false))
            .values).containsEntry("patient_name", "Pat Synthetic");
    }

    /**
     * ZT-M21: two patients read as rows (a selection of rows, not one value): both masked -- by name, email and a national id --
     * from the shared rule (ItemMasks.sensitiveColumnKind). MIG-424's padding of the sample read ["not given", an email] as
     * [not given, email, not given] -- one in three -- and showed the email; not marked sensitive, everything shown.
     */
    @Test
    void aSensitivePipelinesTwoPatientsAreMaskedAsRows() throws Exception {
        output(52L, new Dataset(Arrays.asList("patient_name", "email", "ssn", "risk"), Arrays.asList(
            row("patient_name", "Pat Synthetic", "email", "not given", "ssn", "123456789", "risk", "High"),
            row("patient_name", "Robin Testcase", "email", "robin.testcase@example.test", "ssn", "234-56-7890", "risk", "Low"))));
        wire(step(52L, 0, "decide", "Completed", 2));
        RunResults.Result r = this.results.read(RUN, selection("{\"take\": \"all\"}"), ItemMasks.forWorkspace(JSON.readTree("[]"), true));
        String shown = String.valueOf(r.rows);
        assertThat(shown).doesNotContain("Pat Synthetic").doesNotContain("Robin Testcase").doesNotContain("robin.testcase@example.test")
            .doesNotContain("123456789").doesNotContain("234-56-7890").contains("High").contains("Low");
        assertThat(r.masked).contains("patient_name", "email", "ssn").doesNotContain("risk");
        RunResults.Result open = this.results.read(RUN, selection("{\"take\": \"all\"}"), ItemMasks.forWorkspace(JSON.readTree("[]"), false));
        assertThat(String.valueOf(open.rows)).contains("Pat Synthetic").contains("123456789");
    }

    @Test
    void aSelectionThatDoesNotStandSaysWhereInWords() throws Exception {
        List<String> problems = new ArrayList<>();
        RunResults.Selection.of(JSON.readTree("{\"take\": \"median\", \"maxRows\": 500, \"fields\": [\"a b.c!\", {\"path\": \"x\", "
            + "\"as\": \"1x\"}, \"risk\", {\"path\": \"other\", \"as\": \"risk\"}]}"), problems);
        assertThat(problems).containsExactly("results.take: one of first, all, count, sum, min, max, avg", "results.maxRows: 1 to 100",
            "results.fields[0]: a column, or a column and a dot path into its JSON (summary.risk)",
            "results.fields[1].as: letters, digits and _, starting with a letter", "results.fields[3].as: risk is named twice");
    }

    @Test
    void anOutputPastItsRetentionSaysSo() throws Exception {
        this.kept.put(41L, new StepStore.KeptOutput(41L, "gone", 3L, Instant.now().minusSeconds(5), false));
        wire(step(41L, 0, "old", "Completed", 1));
        assertThat(this.results.read(RUN, new RunResults.Selection(), null).problem).isEqualTo("The run's output is past its retention.");
    }
}
