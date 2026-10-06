package process.forms;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 5 Forms (lite): the rules of a form's fields and of the answers to them -- the server's check, the one that
 * counts, whatever the console already said.
 */
class FormFieldsTest {

    static List<FormField> woundIntake() {
        return Arrays.asList(
            new FormField("patient_id", "Patient ID", "text", true, "As on the wristband", null),
            new FormField("wound_location", "Wound location", "choice", true, null, Arrays.asList("Sacrum", "Heel", "Other")),
            new FormField("length_cm", "Length (cm)", "number", true, null, null),
            new FormField("observed_on", "Observed on", "date", true, null, null),
            new FormField("infection_signs", "Signs of infection", "yesNo", true, null, null),
            new FormField("notes", "Notes", "longText", false, null, null),
            new FormField("nurse_email", "Nurse e-mail", "email", false, null, null));
    }

    // ---- the definition ----------------------------------------------------------------------------------------

    @Test
    void aGoodDefinitionIsKeptTrimmedAndANonChoiceLosesItsOptions() {
        List<FormField> kept = FormFields.valid(Arrays.asList(
            new FormField(" patient_id ", "  Patient ID ", "text", true, "   ", Collections.singletonList("stray")),
            new FormField("site", "Site", "choice", false, " Left side ", Arrays.asList(" Left ", "", "Right"))));

        assertThat(kept.get(0).getKey()).isEqualTo("patient_id");
        assertThat(kept.get(0).getLabel()).isEqualTo("Patient ID");
        assertThat(kept.get(0).getHelp()).isNull();
        assertThat(kept.get(0).getOptions()).isNull();
        assertThat(kept.get(1).getOptions()).containsExactly("Left", "Right");
        assertThat(kept.get(1).getHelp()).isEqualTo("Left side");
        assertThat(FormFields.valid(woundIntake())).hasSize(7);
        assertThat(FormFields.valid(null)).isEmpty();
    }

    @Test
    void everyWayADefinitionIsWrongIsRefusedInASentenceNamingTheField() {
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("Patient", "P", "text", false, null, null))))
            .hasMessageContaining("Field 1 (P)").hasMessageContaining("lower-case");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("submitted_at", "When", "date", false, null, null))))
            .hasMessageContaining("used by the submission itself");
        assertThatThrownBy(() -> FormFields.valid(Arrays.asList(new FormField("a", "A", "text", false, null, null),
            new FormField("a", "B", "text", false, null, null)))).hasMessageContaining("Field 2 (B)").hasMessageContaining("already has");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("a", " ", "text", false, null, null))))
            .hasMessageContaining("give it a label");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("a", "A", "video", false, null, null))))
            .hasMessageContaining("text, longText, number, date, choice, yesNo, email, table, file, signature, lookup");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("a", "A", "choice", false, null, null))))
            .hasMessageContaining("at least one option");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("a", "A", "choice", false, null,
            Arrays.asList("Yes", "yes"))))).hasMessageContaining("listed twice");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(new FormField("a", repeat('L', 121), "text", false, null, null))))
            .hasMessageContaining("label is longer than 120");
        assertThatThrownBy(() -> FormFields.valid(Collections.singletonList(null))).hasMessageContaining("Field 1 is empty");
        List<FormField> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(new FormField("f" + i, "F" + i, "text", false, null, null));
        }
        assertThatThrownBy(() -> FormFields.valid(many)).isInstanceOf(FormFields.Refused.class).hasMessageContaining("at most 50 fields");
    }

    // ---- the answers -------------------------------------------------------------------------------------------

    @Test
    void goodAnswersAreKeptInTheFormsOrderAsTheirTypes() {
        Map<String, Object> given = new HashMap<>();
        given.put("nurse_email", " nora@clinic.example ");
        given.put("patient_id", "  P-00017 ");
        given.put("wound_location", "heel");
        given.put("length_cm", "3.50");
        given.put("observed_on", "2026-10-01");
        given.put("infection_signs", false);
        given.put("notes", "");

        Map<String, Object> kept = FormFields.answers(woundIntake(), given);

        assertThat(new ArrayList<>(kept.keySet())).containsExactly("patient_id", "wound_location", "length_cm", "observed_on",
            "infection_signs", "nurse_email");
        assertThat(kept.get("patient_id")).isEqualTo("P-00017");
        assertThat(kept.get("wound_location")).as("the option's own spelling").isEqualTo("Heel");
        assertThat(kept.get("length_cm")).isEqualTo(new BigDecimal("3.5"));
        assertThat(kept.get("observed_on")).isEqualTo("2026-10-01");
        assertThat(kept.get("infection_signs")).as("no is an answer").isEqualTo(false);
        assertThat(kept.get("nurse_email")).isEqualTo("nora@clinic.example");
        assertThat(kept).doesNotContainKey("notes");

        given.put("length_cm", 4);
        assertThat(FormFields.answers(woundIntake(), given).get("length_cm")).isEqualTo(4L);
    }

    @Test
    void everyWrongAnswerIsReportedAtOnceByItsField() {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("patient_id", "   ");
        given.put("wound_location", "Elbow");
        given.put("length_cm", "three");
        given.put("observed_on", "01/10/2026");
        given.put("infection_signs", "maybe");
        given.put("notes", repeat('n', 5001));
        given.put("nurse_email", "nora");
        given.put("ward", "7B");

        assertThatThrownBy(() -> FormFields.answers(woundIntake(), given))
            .isInstanceOfSatisfying(FormFields.Unanswered.class, refused -> assertThat(refused.getProblems())
                .containsEntry("patient_id", "Patient ID is required.")
                .containsEntry("wound_location", "Wound location: choose one of Sacrum, Heel, Other.")
                .containsEntry("length_cm", "Length (cm): enter a number.")
                .containsEntry("observed_on", "Observed on: enter a date as yyyy-mm-dd.")
                .containsEntry("infection_signs", "Signs of infection: answer yes or no.")
                .containsEntry("notes", "Notes: at most 5000 characters (this is 5001).")
                .containsEntry("nurse_email", "Nurse e-mail: enter an e-mail address, like name@example.com.")
                .containsEntry("ward", "'ward' is not a field of this form.")
                .hasSize(8));
    }

    @Test
    void aRequiredYesNoIsAnsweredByNoButNotByNothing() {
        List<FormField> one = Collections.singletonList(new FormField("consent", "Consent", "yesNo", true, null, null));
        assertThat(FormFields.answers(one, Collections.singletonMap("consent", (Object) "false"))).containsEntry("consent", false);
        assertThatThrownBy(() -> FormFields.answers(one, Collections.<String, Object>emptyMap())).hasMessage("Consent is required.");
        assertThatThrownBy(() -> FormFields.answers(one, null)).hasMessage("Consent is required.");
    }

    @Test
    void anImpossibleDateOrAHugeNumberIsRefused() {
        List<FormField> fields = Arrays.asList(new FormField("d", "D", "date", false, null, null),
            new FormField("n", "N", "number", false, null, null));
        assertThatThrownBy(() -> FormFields.answers(fields, Collections.singletonMap("d", (Object) "2026-02-30"))).hasMessageContaining("yyyy-mm-dd");
        assertThatThrownBy(() -> FormFields.answers(fields, Collections.singletonMap("n", (Object) "1e20"))).hasMessageContaining("below 10^15");
        assertThatThrownBy(() -> FormFields.answers(fields, Collections.singletonMap("n", (Object) true))).hasMessageContaining("enter a number");
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
