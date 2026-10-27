package process.forms;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-10-26 (patient intake, owner): a form's fields may be grouped under section titles, and a "checkboxes" field
 * takes every option that applies (a list of the chosen options, with an optional least and most).
 */
class FormFieldsSectionsCheckboxesTest {

    static FormField checkboxes(String key, String label, boolean required, Integer min, Integer max, String... options) {
        FormField f = new FormField(key, label, FormFields.CHECKBOXES, required, null, Arrays.asList(options));
        f.setMinSelected(min);
        f.setMaxSelected(max);
        return f;
    }

    static FormField sectioned(String key, String label, String type, String section) {
        FormField f = new FormField(key, label, type, false, null, null);
        f.setSection(section);
        return f;
    }

    // ---- sections ------------------------------------------------------------------------------------------------

    @Test
    void aSectionIsKeptTrimmedOnAnyFieldAndABlankOneIsDropped() {
        List<FormField> kept = FormFields.valid(Arrays.asList(
            sectioned("first_name", "First name", "text", "  Patient  "),
            sectioned("consent", "Consent", "yesNo", "Consents"),
            sectioned("notes", "Notes", "longText", "   ")));

        assertThat(kept.get(0).getSection()).isEqualTo("Patient");
        assertThat(kept.get(1).getSection()).isEqualTo("Consents");
        assertThat(kept.get(2).getSection()).isNull();
    }

    @Test
    void aSectionIsOneLineOfAtMost80Characters() {
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(sectioned("a", "A", "text", "x".repeat(81)))))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("section").hasMessageContaining("80");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(sectioned("a", "A", "text", "Patient\nContact"))))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("one line");
    }

    // ---- checkboxes: the definition --------------------------------------------------------------------------------

    @Test
    void checkboxesAreAType() {
        assertThat(FormFields.TYPES).contains("checkboxes");
        assertThat(FormFields.PLACEHOLDER_TYPES).doesNotContain("checkboxes");
        assertThat(FormFields.COLUMN_TYPES).doesNotContain("checkboxes");
    }

    @Test
    void checkboxesKeepTheirOptionsAndBoundsAndAnotherTypeLosesTheBounds() {
        List<FormField> kept = FormFields.valid(Arrays.asList(
            checkboxes("ros", "Symptoms", false, 1, 3, " Fever ", "Cough", "Rash"),
            withBounds(new FormField("name", "Name", "text", false, null, null), 1, 2)));

        assertThat(kept.get(0).getOptions()).containsExactly("Fever", "Cough", "Rash");
        assertThat(kept.get(0).getMinSelected()).isEqualTo(1);
        assertThat(kept.get(0).getMaxSelected()).isEqualTo(3);
        assertThat(kept.get(1).getMinSelected()).isNull();
        assertThat(kept.get(1).getMaxSelected()).isNull();
    }

    @Test
    void checkboxesNeedOptionsAndBoundsThatFitThem() {
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(
            new FormField("ros", "Symptoms", FormFields.CHECKBOXES, false, null, null))))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("at least one option");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(checkboxes("ros", "Symptoms", false, 3, 2, "a", "b", "c"))))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("minSelected");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(checkboxes("ros", "Symptoms", false, null, 4, "a", "b", "c"))))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("maxSelected");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(checkboxes("ros", "Symptoms", false, -1, null, "a"))))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("minSelected");
    }

    @Test
    void aRuleOnCheckboxesTestsWhatWasTickedNeverOrder() {
        FormField greater = new FormField("follow", "Follow up", "text", false, null, null);
        greater.setShowWhen(new FormField.Rule("ros", "gt", "Fever"));
        assertThatThrownBy(() -> FormFields.valid(Arrays.asList(checkboxes("ros", "Symptoms", false, null, null, "Fever", "Cough"), greater)))
            .isInstanceOf(FormFields.Refused.class).hasMessageContaining("checkboxes");

        FormField shown = new FormField("fever_days", "Days of fever", "number", false, null, null);
        shown.setShowWhen(new FormField.Rule("ros", "eq", "fever"));
        shown.setRequiredWhen(new FormField.Rule("ros", "eq", "fever"));
        List<FormField> form = FormFields.valid(Arrays.asList(checkboxes("ros", "Symptoms", false, null, null, "Fever", "Cough"), shown));

        Map<String, Object> coughOnly = new LinkedHashMap<>();
        coughOnly.put("ros", Collections.singletonList("Cough"));
        coughOnly.put("fever_days", 3);
        assertThat(FormFields.answers(form, coughOnly)).containsOnlyKeys("ros");   // not shown: dropped

        Map<String, Object> fever = new LinkedHashMap<>();
        fever.put("ros", Arrays.asList("Cough", "Fever"));
        assertThatThrownBy(() -> FormFields.answers(form, fever)).isInstanceOf(FormFields.Unanswered.class)
            .hasMessageContaining("Days of fever is required");
    }

    // ---- checkboxes: the answers ---------------------------------------------------------------------------------

    @Test
    void aCheckboxesAnswerIsTheTickedOptionsInTheirOwnOrderAndSpelling() {
        List<FormField> form = FormFields.valid(Collections.singletonList(checkboxes("ros", "Symptoms", true, null, null, "Fever", "Cough", "Rash")));
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("ros", Arrays.asList(" rash", "FEVER", "Fever"));

        assertThat(FormFields.answers(form, given).get("ros")).isEqualTo(Arrays.asList("Fever", "Rash"));

        Map<String, Object> one = new LinkedHashMap<>();
        one.put("ros", "cough");   // one option sent as text is a list of one
        assertThat(FormFields.answers(form, one).get("ros")).isEqualTo(Collections.singletonList("Cough"));
    }

    @Test
    void aCheckboxesAnswerIsRefusedForAnUnknownOptionTheBoundsOrNothingTickedWhenRequired() {
        List<FormField> form = FormFields.valid(Collections.singletonList(checkboxes("ros", "Symptoms", true, 2, 2, "Fever", "Cough", "Rash")));

        assertThatThrownBy(() -> FormFields.answers(form, Map.of("ros", List.of("Fever", "Headache"))))
            .isInstanceOf(FormFields.Unanswered.class).hasMessageContaining("Headache").hasMessageContaining("Fever, Cough, Rash");
        assertThatThrownBy(() -> FormFields.answers(form, Map.of("ros", List.of("Fever"))))
            .isInstanceOf(FormFields.Unanswered.class).hasMessageContaining("at least 2");
        assertThatThrownBy(() -> FormFields.answers(form, Map.of("ros", List.of("Fever", "Cough", "Rash"))))
            .isInstanceOf(FormFields.Unanswered.class).hasMessageContaining("at most 2");
        assertThatThrownBy(() -> FormFields.answers(form, Map.of("ros", List.of())))
            .isInstanceOf(FormFields.Unanswered.class).hasMessageContaining("Symptoms is required");
        assertThatThrownBy(() -> FormFields.answers(form, Map.of("ros", List.of(Map.of("a", 1)))))
            .isInstanceOf(FormFields.Unanswered.class).hasMessageContaining("tick");
    }

    @Test
    void anOptionalCheckboxesWithNothingTickedIsLeftOutAndItsLeastDoesNotApply() {
        List<FormField> form = FormFields.valid(Collections.singletonList(checkboxes("ros", "Symptoms", false, 2, null, "Fever", "Cough", "Rash")));

        assertThat(FormFields.answers(form, Map.of("ros", List.of()))).isEmpty();
    }

    // ---- where the answer goes ------------------------------------------------------------------------------------

    @Test
    void theDatasetKeepsCheckboxesAsAListAndTheCsvJoinsThem() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("ros", Arrays.asList("Fever", "Rash"));
        answers.put("meds", Collections.singletonList(Map.of("name", "Metformin")));
        FormStore.Submission s = new FormStore.Submission(7L, 3L, 1006L, 1, answers, 1015L, "Front desk",
            Instant.parse("2026-10-26T15:00:00Z"), "Received", null, null, null, null, null);
        List<FormField> fields = Arrays.asList(checkboxes("ros", "Symptoms", false, null, null, "Fever", "Rash"),
            tableOf("meds"));

        Map<String, Object> row = FormDatasets.rowOf(fields, s);

        assertThat(row.get("ros")).isEqualTo(Arrays.asList("Fever", "Rash"));
        assertThat(row.get("meds")).isEqualTo("[{\"name\":\"Metformin\"}]");   // a table stays JSON text, as before
        assertThat(FormSubmissionService.csvAnswer(Arrays.asList("Fever", "Rash"))).isEqualTo("Fever; Rash");
        assertThat(FormSubmissionService.csvAnswer(Collections.singletonList(Map.of("name", "Metformin")))).isEqualTo("[{\"name\":\"Metformin\"}]");
    }

    private static FormField tableOf(String key) {
        FormField t = new FormField(key, "Medicines", FormFields.TABLE, false, null, null);
        t.setColumns(Collections.singletonList(new FormField("name", "Name", "text", false, null, null)));
        return t;
    }

    private static FormField withBounds(FormField f, Integer min, Integer max) {
        f.setMinSelected(min);
        f.setMaxSelected(max);
        return f;
    }
}
