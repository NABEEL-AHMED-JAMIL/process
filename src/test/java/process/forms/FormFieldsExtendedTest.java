package process.forms;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-277: tables, files, signatures, lookups and show/required rules -- what a builder may define, and what an answer
 * to each must be. The context stands in for the store: two uploads by this person, one of them a PNG; a lookup that
 * offers two patients.
 */
class FormFieldsExtendedTest {

    private static final FormFields.Context CONTEXT = new FormFields.Context() {
        @Override
        public List<String> lookupValues(FormField field) {
            return Arrays.asList("P-100", "P-200");
        }

        @Override
        public Optional<FormFields.Upload> upload(String fieldKey, long uploadId) {
            if (uploadId == 11 && "photo".equals(fieldKey)) {
                return Optional.of(new FormFields.Upload(11, "wound.jpg", "image/jpeg", 2048, "inbox", "intake/forms/form-1/uploads/a-wound.jpg"));
            }
            if (uploadId == 12 && "photo".equals(fieldKey)) {
                return Optional.of(new FormFields.Upload(12, "second.jpg", "image/jpeg", 1024, "inbox", "intake/forms/form-1/uploads/b.jpg"));
            }
            if (uploadId == 21 && "signed".equals(fieldKey)) {
                return Optional.of(new FormFields.Upload(21, "signature.png", "image/png", 900, "inbox", "intake/forms/form-1/uploads/s.png"));
            }
            return Optional.empty();
        }
    };

    static FormField field(String key, String label, String type, boolean required) {
        return new FormField(key, label, type, required, null, null);
    }

    static List<FormField> visit() {
        FormField patient = field("patient", "Patient", "lookup", true);
        patient.setLookup(new FormField.Lookup(900L, "patient_id"));
        FormField infected = field("infected", "Infected", "yesNo", true);
        FormField antibiotic = field("antibiotic", "Antibiotic", "text", false);
        antibiotic.setShowWhen(new FormField.Rule("infected", "eq", true));
        antibiotic.setRequiredWhen(new FormField.Rule("infected", "eq", "true"));
        FormField doses = field("doses", "Doses", "table", false);
        doses.setColumns(Arrays.asList(field("drug", "Drug", "text", true), field("mg", "mg", "number", false)));
        doses.setMaxRows(3);
        FormField photo = field("photo", "Photo", "file", false);
        photo.setAccept(Arrays.asList("JPG", ".png"));
        photo.setMaxFiles(2);
        FormField signed = field("signed", "Signed by the nurse", "signature", true);
        return FormFields.valid(Arrays.asList(patient, infected, antibiotic, doses, photo, signed));
    }

    // ---- the definition ----------------------------------------------------------------------------------------

    @Test
    void theNewTypesAreKeptWithTheirDefaults() {
        List<FormField> kept = visit();
        assertThat(kept.get(3).getMaxRows()).isEqualTo(3);
        assertThat(kept.get(3).getColumns()).extracting(FormField::getKey).containsExactly("drug", "mg");
        assertThat(kept.get(4).getAccept()).containsExactly("jpg", "png");
        assertThat(kept.get(4).getMaxSizeMb()).isEqualTo(10);
        assertThat(kept.get(4).getMaxFiles()).isEqualTo(2);
        assertThat(kept.get(2).getShowWhen().getField()).isEqualTo("infected");

        FormField plainFile = field("scan", "Scan", "file", false);
        assertThat(FormFields.valid(Collections.singletonList(plainFile)).get(0).getAccept()).isEqualTo(FormFields.DEFAULT_ACCEPT);
    }

    @Test
    void aRuleNamesAnEarlierFieldAndATestItsTypeAllows() {
        FormField later = field("b", "B", "text", false);
        later.setShowWhen(new FormField.Rule("c", "eq", "x"));
        assertThatThrownBy(() -> FormFields.valid(Arrays.asList(field("a", "A", "text", false), later, field("c", "C", "text", false))))
            .hasMessageContaining("only by a field above it");

        FormField onTable = field("n", "N", "text", false);
        onTable.setShowWhen(new FormField.Rule("t", "eq", "x"));
        FormField table = field("t", "T", "table", false);
        table.setColumns(Collections.singletonList(field("x", "X", "text", false)));
        assertThatThrownBy(() -> FormFields.valid(Arrays.asList(table, onTable))).hasMessageContaining("filled or empty");

        FormField noValue = field("n", "N", "text", false);
        noValue.setRequiredWhen(new FormField.Rule("a", "eq", " "));
        assertThatThrownBy(() -> FormFields.valid(Arrays.asList(field("a", "A", "text", false), noValue)))
            .hasMessageContaining("compared with");

        FormField badOp = field("n", "N", "text", false);
        badOp.setShowWhen(new FormField.Rule("a", "like", "x"));
        assertThatThrownBy(() -> FormFields.valid(Arrays.asList(field("a", "A", "text", false), badOp))).hasMessageContaining("test must be");
    }

    @Test
    void aDefinitionOfTheNewTypesIsRefusedWhenIncomplete() {
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(field("t", "T", "table", false))))
            .hasMessageContaining("at least one column");
        FormField nested = field("t", "T", "table", false);
        nested.setColumns(Collections.singletonList(field("inner", "Inner", "table", false)));
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(nested))).hasMessageContaining("a column's type");
        FormField exe = field("f", "F", "file", false);
        exe.setAccept(Collections.singletonList("exe"));
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(exe))).hasMessageContaining("'exe' files cannot be accepted");
        FormField huge = field("f", "F", "file", false);
        huge.setMaxSizeMb(500);
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(huge))).hasMessageContaining("maxSizeMb is from 1 to 25");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(field("l", "L", "lookup", false))))
            .hasMessageContaining("names the form and the field");
    }

    // ---- the answers -------------------------------------------------------------------------------------------

    static Map<String, Object> answers() {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("patient", "p-200");
        given.put("infected", false);
        given.put("antibiotic", "Amoxicillin");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("drug", "Paracetamol");
        row.put("mg", "500");
        rows.add(row);
        rows.add(new HashMap<>());
        given.put("doses", rows);
        given.put("photo", Arrays.asList(11, Collections.singletonMap("uploadId", 12)));
        given.put("signed", 21);
        return given;
    }

    @Test
    void goodAnswersAreKeptEachAsItsTypeSays() {
        Map<String, Object> kept = FormFields.answers(visit(), answers(), CONTEXT);

        assertThat(kept.get("patient")).as("the lookup's own spelling").isEqualTo("P-200");
        assertThat(kept).as("a field not shown is not asked: its answer is dropped").doesNotContainKey("antibiotic");
        assertThat((List<?>) kept.get("doses")).as("the blank row is no row").hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) kept.get("doses")).get(0)).get("mg")).isEqualTo(500L);
        assertThat((List<?>) kept.get("photo")).extracting(p -> (Object) ((Map<?, ?>) p).get("key"))
            .containsExactly((Object) "intake/forms/form-1/uploads/a-wound.jpg", (Object) "intake/forms/form-1/uploads/b.jpg");
        assertThat(((Map<?, ?>) kept.get("signed")).get("contentType")).isEqualTo("image/png");
    }

    @Test
    void aShownFieldCanBecomeRequired() {
        Map<String, Object> given = answers();
        given.put("infected", true);
        given.remove("antibiotic");
        assertThatThrownBy(() -> FormFields.answers(visit(), given, CONTEXT)).isInstanceOf(FormFields.Unanswered.class)
            .hasMessage("Antibiotic is required.");
        given.put("antibiotic", "Amoxicillin");
        assertThat(FormFields.answers(visit(), given, CONTEXT)).containsEntry("antibiotic", "Amoxicillin");
    }

    @Test
    void badAnswersToTheNewTypesAreNamedByRowOrFile() {
        Map<String, Object> given = answers();
        given.put("patient", "P-999");
        Map<String, Object> noDrug = new HashMap<>();
        noDrug.put("mg", "a lot");
        noDrug.put("route", "oral");
        given.put("doses", Collections.singletonList(noDrug));
        given.put("photo", Arrays.asList(11, 11));
        given.put("signed", 11);

        try {
            FormFields.answers(visit(), given, CONTEXT);
            throw new AssertionError("refused");
        } catch (FormFields.Unanswered unanswered) {
            Map<String, String> problems = unanswered.getProblems();
            assertThat(problems.get("patient")).contains("choose one of the listed values");
            assertThat(problems.get("doses")).contains("row 1, Drug is required.").contains("row 1, mg: enter a number.")
                .contains("row 1: 'route' is not a column.");
            assertThat(problems.get("photo")).as("one file twice").contains("sent already");
            assertThat(problems.get("signed")).as("another field's upload").contains("not yours to send");
        }
    }

    @Test
    void limitsOnRowsAndFilesHold() {
        Map<String, Object> given = answers();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(Collections.singletonMap("drug", "D" + i));
        }
        given.put("doses", rows);
        given.put("photo", Arrays.asList(11, 12, 13));
        try {
            FormFields.answers(visit(), given, CONTEXT);
            throw new AssertionError("refused");
        } catch (FormFields.Unanswered unanswered) {
            assertThat(unanswered.getProblems().get("doses")).contains("at most 3 rows (this has 4)");
            assertThat(unanswered.getProblems().get("photo")).contains("at most 2 files");
        }
    }

    @Test
    void theRulesOperatorsReadNumbersDatesAndWords() {
        Map<String, Object> kept = new HashMap<>();
        kept.put("n", 12L);
        kept.put("d", "2026-10-01");
        kept.put("w", "Heel");
        assertThat(FormFields.holds(new FormField.Rule("n", "gt", "10"), kept)).isTrue();
        assertThat(FormFields.holds(new FormField.Rule("n", "lt", 10), kept)).isFalse();
        assertThat(FormFields.holds(new FormField.Rule("d", "gt", "2026-09-30"), kept)).isTrue();
        assertThat(FormFields.holds(new FormField.Rule("w", "in", Arrays.asList("sacrum", "heel")), kept)).isTrue();
        assertThat(FormFields.holds(new FormField.Rule("w", "ne", "heel"), kept)).isFalse();
        assertThat(FormFields.holds(new FormField.Rule("missing", "empty", null), kept)).isTrue();
        assertThat(FormFields.holds(new FormField.Rule("missing", "ne", "x"), kept)).isTrue();
        assertThat(FormFields.holds(new FormField.Rule("w", "filled", null), kept)).isTrue();
    }

    @Test
    void aFormOfPlainFieldsAnswersAsBefore() {
        Map<String, Object> answers = new HashMap<>();
        answers.put("patient_id", "P-1");
        answers.put("wound_location", "heel");
        answers.put("length_cm", "3.5");
        answers.put("observed_on", "2026-09-30");
        answers.put("infection_signs", "false");
        assertThat(FormFields.answers(FormFieldsTest.woundIntake(), answers)).containsEntry("wound_location", "Heel")
            .containsEntry("infection_signs", false);
    }
}
