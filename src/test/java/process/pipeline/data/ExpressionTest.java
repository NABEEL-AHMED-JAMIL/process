package process.pipeline.data;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-255: the Compute step's formulas -- arithmetic, comparisons, and/or/not, if, round, coalesce and dates over a row's
 * columns. A column read as CSV text is a number where it reads as one. An empty or null operand makes arithmetic null
 * (never an error), dividing by zero is null, and a comparison with null is false. A formula is parsed once and checked
 * when the definition is saved, so a typo is a definition problem, not a failed run.
 */
class ExpressionTest {

    private static Object eval(String formula, Object... columns) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < columns.length; i += 2) {
            row.put((String) columns[i], columns[i + 1]);
        }
        return Expression.parse(formula).evaluate(row);
    }

    @Test
    void arithmeticReadsTextColumnsAsNumbersAndKeepsPrecedence() {
        assertThat(eval("length_cm * width_cm", "length_cm", "4", "width_cm", 2.5)).isEqualTo(10.0);
        assertThat(eval("1 + 2 * 3 - 4 / 2")).isEqualTo(5.0);
        assertThat(eval("(1 + 2) * 3")).isEqualTo(9.0);
        assertThat(eval("-a + 1", "a", 3)).isEqualTo(-2.0);
    }

    @Test
    void roundAndAbsAndMinMax() {
        assertThat(eval("round(a * 0.785, 1)", "a", 10)).isEqualTo(7.9);
        assertThat(eval("round(2.5)")).isEqualTo(3L);
        assertThat(eval("abs(0 - 4)")).isEqualTo(4.0);
        assertThat(eval("min(3, a, 7)", "a", "2")).isEqualTo(2.0);
        assertThat(eval("max(3, a, 7)", "a", "2")).isEqualTo(7.0);
    }

    @Test
    void nullsNeverThrow() {
        assertThat(eval("a * 2", "a", null)).isNull();
        assertThat(eval("a * 2", "a", "")).isNull();
        assertThat(eval("a * 2")).isNull();
        assertThat(eval("a / b", "a", 1, "b", 0)).isNull();
        assertThat(eval("a > 1", "a", null)).isEqualTo(false);
        assertThat(eval("coalesce(a, b, 0)", "a", null, "b", "")).isEqualTo(0.0);
        assertThat(eval("is_null(a)", "a", "")).isEqualTo(true);
    }

    @Test
    void conditionsAndTextChoose() {
        String trend = "if(is_null(prev), 'baseline', if(change <= -10, 'improving', if(change >= 10, 'worsening', 'stable')))";
        assertThat(eval(trend, "prev", null, "change", null)).isEqualTo("baseline");
        assertThat(eval(trend, "prev", 12, "change", "-25.5")).isEqualTo("improving");
        assertThat(eval(trend, "prev", 12, "change", 3)).isEqualTo("stable");
        assertThat(eval(trend, "prev", 12, "change", 40)).isEqualTo("worsening");
        assertThat(eval("a > 1 and not (b == 'x') or false", "a", 2, "b", "y")).isEqualTo(true);
        assertThat(eval("concat(site, ' / ', stage)", "site", "heel", "stage", 2)).isEqualTo("heel / 2");
    }

    @Test
    void textComparesAsTextAndDatesCount() {
        assertThat(eval("visit_date > '2026-09-01'", "visit_date", "2026-09-15")).isEqualTo(true);
        assertThat(eval("days_between(prev_date, visit_date)", "prev_date", "2026-09-01", "visit_date", "2026-09-15")).isEqualTo(14L);
        assertThat(eval("days_between(prev_date, visit_date)", "prev_date", null, "visit_date", "2026-09-15")).isNull();
        assertThat(eval("`wound length` + 1", "wound length", 2)).isEqualTo(3.0);
        String today = LocalDate.now(ZoneId.of("America/Chicago")).toString();
        assertThat(eval("today()", "x", 1)).isEqualTo(today);
        assertThat(eval("round(days_between(birth, today()) / 365.25, 0)", "birth", today)).isEqualTo(0L);
        assertThat(eval("regex_extract(k, '-([a-z]+-[0-9]{3})[.]jpe?g$')", "k", "intake/2026-10-06/88-scan-007.jpeg")).isEqualTo("scan-007");
        assertThat(eval("regex_extract(k, '[0-9]+')", "k", "abc 42 x")).isEqualTo("42");
        assertThat(eval("regex_extract(k, 'z')", "k", "abc")).isNull();
        assertThat(eval("regex_extract(k, 'z')", "k", null)).isNull();
        assertThatThrownBy(() -> eval("regex_extract(k, '(')", "k", "a")).hasMessageContaining("is not a valid pattern");
    }

    @Test
    void aTextThatIsNotANumberFailsTheRowWithItsColumn() {
        assertThatThrownBy(() -> eval("a * 2", "a", "abc")).hasMessage("'abc' in a is not a number.");
    }

    @Test
    void aTypoIsASyntaxErrorAtItsPlace() {
        assertThatThrownBy(() -> Expression.parse("a * (b + 1")).hasMessageContaining("expected ')'");
        assertThatThrownBy(() -> Expression.parse("sqrt(a)")).hasMessage("Unknown function sqrt; use one of abs, coalesce, concat, days_between, "
            + "if, is_null, max, min, number, regex_extract, round, text, today.");
        assertThatThrownBy(() -> Expression.parse("regex_extract(a)")).hasMessage("regex_extract takes 2 values: regex_extract(text, pattern).");
        assertThatThrownBy(() -> Expression.parse("today(a)")).hasMessage("today takes no values: today().");
        assertThatThrownBy(() -> Expression.parse("abs()")).hasMessage("abs takes at least 1 value.");
        assertThatThrownBy(() -> Expression.parse("if(a, b)")).hasMessage("if takes 3 values: if(condition, then, otherwise).");
        assertThatThrownBy(() -> Expression.parse("a +")).hasMessageContaining("at 4");
        assertThat(Expression.parse("round(a * b, 1)").columns()).containsExactly("a", "b");
    }
}
