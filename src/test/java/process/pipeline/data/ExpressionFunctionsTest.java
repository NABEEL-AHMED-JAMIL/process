package process.pipeline.data;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-365 and MIG-346: Compute's number functions (floor, ceil, mod), date functions (date_add, date_diff, date_trunc)
 * and JSON functions (json_get, json_array_length, json_keys). Null in, null out; a value that is not what the function
 * needs fails the row naming its column, as every other function does.
 */
class ExpressionFunctionsTest {

    private static Object eval(String formula, Object... columns) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < columns.length; i += 2) {
            row.put((String) columns[i], columns[i + 1]);
        }
        return Expression.parse(formula).evaluate(row);
    }

    @Test
    void floorAndCeilAreWholeNumbers() {
        assertThat(eval("floor(a)", "a", "2.7")).isEqualTo(2L);
        assertThat(eval("floor(-2.1)")).isEqualTo(-3L);
        assertThat(eval("ceil(a)", "a", 2.1)).isEqualTo(3L);
        assertThat(eval("ceil(-2.9)")).isEqualTo(-2L);
        assertThat(eval("floor(a / 10) * 10", "a", 47)).isEqualTo(40.0);
        assertThat(eval("floor(a)", "a", null)).isNull();
        assertThat(eval("floor(a)", "a", "")).isNull();
        assertThatThrownBy(() -> eval("ceil(a)", "a", "north")).hasMessageContaining("'north' in a is not a number");
    }

    @Test
    void modHasTheDivisorsSignAsASpreadsheetDoes() {
        assertThat(eval("mod(a, 3)", "a", 7)).isEqualTo(1L);
        assertThat(eval("mod(-7, 3)")).isEqualTo(2L);
        assertThat(eval("mod(7, -3)")).isEqualTo(-2L);
        assertThat(eval("mod(a, 2) == 0", "a", "10")).isEqualTo(true);
        assertThat((Double) eval("mod(5.5, 2)")).isEqualTo(1.5);
        assertThat(eval("mod(5, 0)")).isNull();
        assertThat(eval("mod(a, 2)", "a", null)).isNull();
        assertThatThrownBy(() -> Expression.parse("mod(5)")).hasMessageContaining("mod takes 2 values");
    }

    @Test
    void dateAddKeepsTheFormItWasGiven() {
        assertThat(eval("date_add(d, 30)", "d", "2026-01-15")).isEqualTo("2026-02-14");
        assertThat(eval("date_add(d, 1, 'month')", "d", "2026-01-31")).isEqualTo("2026-02-28");
        assertThat(eval("date_add(d, -1, 'year')", "d", "2024-02-29")).isEqualTo("2023-02-28");
        assertThat(eval("date_add(d, 2, 'weeks')", "d", "2026-10-01")).isEqualTo("2026-10-15");
        assertThat(eval("date_add(d, 1, 'quarter')", "d", "2026-11-30")).isEqualTo("2027-02-28");
        assertThat(eval("date_add(d, 6, 'hours')", "d", "2026-10-01")).isEqualTo("2026-10-01T06:00");
        assertThat(eval("date_add(d, 90, 'minute')", "d", "2026-10-01T23:00")).isEqualTo("2026-10-02T00:30");
        assertThat(eval("date_add(d, 1, 'day')", "d", "2026-10-01 08:15:30")).isEqualTo("2026-10-02T08:15:30");
        assertThat(eval("date_add(d, 2, 'hour')", "d", "2026-10-01T23:00:00Z")).isEqualTo("2026-10-02T01:00:00Z");
        assertThat(eval("date_add(d, 1, 'day')", "d", "2026-10-01T10:00:00-05:00")).isEqualTo("2026-10-02T10:00:00-05:00");
        assertThat(eval("date_add(d, n, 'day')", "d", "2026-10-01", "n", "3")).isEqualTo("2026-10-04");
        assertThat(eval("date_add(d, 1)", "d", null)).isNull();
        assertThatThrownBy(() -> eval("date_add(d, 1.5)", "d", "2026-10-01")).hasMessageContaining("whole units");
        assertThatThrownBy(() -> eval("date_add(d, 1, 'fortnight')", "d", "2026-10-01")).hasMessageContaining("'fortnight' is not a unit");
        assertThatThrownBy(() -> eval("date_add(d, 1)", "d", "tomorrow")).hasMessageContaining("'tomorrow' in d is not a date");
    }

    @Test
    void dateDiffCountsWholeUnits() {
        assertThat(eval("date_diff(a, b)", "a", "2026-01-01", "b", "2026-03-01")).isEqualTo(59L);
        assertThat(eval("date_diff(a, b, 'month')", "a", "2026-01-31", "b", "2026-03-01")).isEqualTo(1L);
        assertThat(eval("date_diff(a, b, 'years')", "a", "1980-06-15", "b", "2026-06-14")).isEqualTo(45L);
        assertThat(eval("date_diff(a, b, 'week')", "a", "2026-10-01", "b", "2026-10-22")).isEqualTo(3L);
        assertThat(eval("date_diff(a, b, 'quarter')", "a", "2026-01-01", "b", "2026-10-01")).isEqualTo(3L);
        assertThat(eval("date_diff(a, b, 'hour')", "a", "2026-10-01", "b", "2026-10-02T06:30")).isEqualTo(30L);
        assertThat(eval("date_diff(a, b, 'minute')", "a", "2026-10-01T10:00:00Z", "b", "2026-10-01T06:00:00-05:00")).isEqualTo(60L);
        assertThat(eval("date_diff(b, a)", "a", "2026-01-01", "b", "2026-01-11")).isEqualTo(-10L);
        assertThat(eval("date_diff(a, b)", "a", "", "b", "2026-01-11")).isNull();
    }

    @Test
    void dateTruncTakesEitherOrder() {
        assertThat(eval("date_trunc(d, 'month')", "d", "2026-10-12")).isEqualTo("2026-10-01");
        assertThat(eval("date_trunc('month', d)", "d", "2026-10-12")).isEqualTo("2026-10-01");
        assertThat(eval("date_trunc(d, 'year')", "d", "2026-10-12")).isEqualTo("2026-01-01");
        assertThat(eval("date_trunc(d, 'quarter')", "d", "2026-08-12")).isEqualTo("2026-07-01");
        assertThat(eval("date_trunc(d, 'week')", "d", "2026-10-11")).isEqualTo("2026-10-05");
        assertThat(eval("date_trunc(d, 'hour')", "d", "2026-10-12T14:35:20")).isEqualTo("2026-10-12T14:00:00");
        assertThat(eval("date_trunc(d, 'day')", "d", "2026-10-12T14:35")).isEqualTo("2026-10-12T00:00");
        assertThat(eval("date_trunc(d, 'month')", "d", "2026-10-12T14:35:00Z")).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(eval("date_trunc(d, 'hour')", "d", "2026-10-12")).isEqualTo("2026-10-12");
        assertThatThrownBy(() -> eval("date_trunc(d, u)", "d", "2026-10-12", "u", null)).hasMessageContaining("needs a unit");
    }

    @Test
    void jsonGetReadsAPathAndKeepsNestedValuesAsJsonText() {
        String json = "{\"patient\":{\"id\":\"P-1\",\"age\":61},\"labs\":[{\"code\":\"HbA1c\",\"value\":7.2},{\"code\":\"LDL\",\"value\":130}],"
            + "\"flag\":true,\"none\":null}";
        assertThat(eval("json_get(j, 'patient.id')", "j", json)).isEqualTo("P-1");
        assertThat(eval("json_get(j, '$.patient.age')", "j", json)).isEqualTo(61L);
        assertThat(eval("json_get(j, 'labs[1].value')", "j", json)).isEqualTo(130L);
        assertThat(eval("json_get(j, 'labs[0].value') > 7", "j", json)).isEqualTo(true);
        assertThat(eval("json_get(j, 'flag')", "j", json)).isEqualTo(true);
        assertThat(eval("json_get(j, 'patient')", "j", json)).isEqualTo("{\"id\":\"P-1\",\"age\":61}");
        assertThat(eval("json_get(j, 'missing.path')", "j", json)).isNull();
        assertThat(eval("json_get(j, 'none')", "j", json)).isNull();
        assertThat(eval("json_get(j, 'labs[9].code')", "j", json)).isNull();
        assertThat(eval("json_get(j, '[1]')", "j", "[10, 20]")).isEqualTo(20L);
        assertThat(eval("json_get(j, 'a')", "j", null)).isNull();
        assertThatThrownBy(() -> eval("json_get(j, 'a')", "j", "{not json")).hasMessageContaining("The value in j is not JSON");
    }

    @Test
    void jsonArrayLengthAndKeys() {
        String json = "{\"labs\":[1,2,3],\"meta\":{\"b\":1,\"a\":2}}";
        assertThat(eval("json_array_length(j, 'labs')", "j", json)).isEqualTo(3L);
        assertThat(eval("json_array_length(j)", "j", "[]")).isEqualTo(0L);
        assertThat(eval("json_array_length(j, 'nope')", "j", json)).isNull();
        assertThat(eval("json_keys(j)", "j", json)).isEqualTo("[\"labs\",\"meta\"]");
        assertThat(eval("json_keys(j, 'meta')", "j", json)).isEqualTo("[\"b\",\"a\"]");
        assertThat(eval("json_array_length(json_keys(j, 'meta'))", "j", json)).isEqualTo(2L);
        assertThatThrownBy(() -> eval("json_array_length(j, 'meta')", "j", json)).hasMessageContaining("not a JSON array");
        assertThatThrownBy(() -> eval("json_keys(j, 'labs')", "j", json)).hasMessageContaining("not a JSON object");
    }

    @Test
    void theParseCacheDoesNotLeakOneRowsJsonIntoTheNext() {
        Expression get = Expression.parse("json_get(j, 'a')");
        Map<String, Object> one = new HashMap<>();
        one.put("j", "{\"a\":1}");
        Map<String, Object> two = new HashMap<>();
        two.put("j", "{\"a\":2}");
        assertThat(get.evaluate(one)).isEqualTo(1L);
        assertThat(get.evaluate(two)).isEqualTo(2L);
        assertThat(get.evaluate(one)).isEqualTo(1L);
    }

    @Test
    void unknownFunctionListsTheNewOnes() {
        assertThatThrownBy(() -> Expression.parse("explode(x)")).hasMessageContaining("date_add").hasMessageContaining("json_get")
            .hasMessageContaining("floor");
    }
}
