package process.pipeline.items;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.sensitive.SensitiveValues.Kind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ZT-M21: a flattened JSON path names a column as an underscore does -- "patient.name" is a patient's name, "contact.address"
 * an address, "patient.ssn" a national id -- so "." "_" and "-" are one separator to the column rules. Synthetic values.
 */
class ItemMasksColumnNameTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aDotIsTheSameSeparatorAsAnUnderscoreOrADash() {
        for (String column : Arrays.asList("patient.name", "patient_name", "patient-name", "Patient.Name")) {
            assertThat(ItemMasks.sensitiveColumnKind(column, Collections.singletonList("Pat Synthetic"))).as(column).contains(Kind.PERSON_NAME);
        }
        assertThat(ItemMasks.sensitiveColumnKind("contact.address", Collections.singletonList("12 Elm Street"))).contains(Kind.ADDRESS);
        assertThat(ItemMasks.sensitiveColumnKind("patient.ssn", Collections.singletonList("123456789"))).contains(Kind.NATIONAL_ID);
        // Three values or more: the platform's rule, the dot read the same way.
        assertThat(ItemMasks.sensitiveColumnKind("patient.name", Arrays.asList("Pat Synthetic", "Robin Testcase", "Sam Example")))
            .contains(Kind.PERSON_NAME);
        // What is not a person's name stays so: a product's name, a file's.
        assertThat(ItemMasks.sensitiveColumnKind("product.name", Collections.singletonList("Wound Care Kit"))).isEmpty();
        assertThat(ItemMasks.sensitiveColumnKind("file.name", Collections.singletonList("Intake Form"))).isEmpty();
        assertThat(ItemMasks.columnName(" patient.name ")).isEqualTo("patient_name");
    }

    @Test
    void aSensitivePipelinesFlattenedColumnsAreMaskedFromOneRow() throws Exception {
        ItemMasks masks = ItemMasks.forWorkspace(JSON.readTree("[]"), true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patient.name", "Pat Synthetic");
        row.put("contact.address", "12 Elm Street");
        row.put("status.risk", "High");
        Set<String> masked = new HashSet<>();
        List<Map<String, Object>> shown = masks.rows(Collections.singletonList(row), row.keySet(), masked);
        assertThat(shown.toString()).doesNotContain("Pat Synthetic").doesNotContain("12 Elm Street").contains("High");
        assertThat(masked).containsExactlyInAnyOrder("patient.name", "contact.address");
    }
}
