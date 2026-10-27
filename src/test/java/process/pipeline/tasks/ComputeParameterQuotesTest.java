package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.RunParameters;
import process.pipeline.data.Expression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ZT-I26 (patient intake, 2026-10-26): a run parameter (or run value) put into a Compute formula's quoted text broke the
 * formula when the value held that quote -- a reviewer called O'Brien, a note "said 'no'" -- and a value could end the text
 * and add to the formula. A quote inside quoted text is now written twice (as SQL does) and read as one, so the value is
 * always just text; outside quotes a value goes in as before.
 */
class ComputeParameterQuotesTest {

    private static Map<String, Object> computing(String expression) {
        Map<String, Object> formula = new LinkedHashMap<>();
        formula.put("target", "reviewed_by");
        formula.put("expression", expression);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("formulas", List.of(formula));
        return config;
    }

    @SuppressWarnings("unchecked")
    private static Object evaluate(Map<String, Object> filled) {
        Map<String, Object> formula = ((List<Map<String, Object>>) filled.get("formulas")).get(0);
        return Expression.parse((String) formula.get("expression")).evaluate(Collections.emptyMap());
    }

    private static Map<String, Object> fill(String expression, String value) {
        return RunParameters.fill(computing(expression), Map.of("param.reviewed_by", value), Collections.emptySet(),
            RunParameters.expressionFields(ComputeStepTask.SPEC.configSchema()));
    }

    @Test
    void computeDeclaresItsFormulasAsExpressions() {
        assertThat(RunParameters.expressionFields(ComputeStepTask.SPEC.configSchema())).containsExactly("formulas");
    }

    @Test
    void aValueWithTheEnclosingQuoteStaysOneText() {
        assertThat(evaluate(fill("'{{param.reviewed_by}}'", "Dana O'Brien"))).isEqualTo("Dana O'Brien");
        assertThat(evaluate(fill("\"{{param.reviewed_by}}\"", "said \"no\" twice"))).isEqualTo("said \"no\" twice");
        assertThat(evaluate(fill("concat('By ', '{{param.reviewed_by}}', '.')", "O'Brien"))).isEqualTo("By O'Brien.");
        // the other quote inside a text needs nothing
        assertThat(evaluate(fill("'{{param.reviewed_by}}'", "said \"no\""))).isEqualTo("said \"no\"");
    }

    @Test
    void aValueCannotEndTheTextAndAddToTheFormula() {
        assertThat(evaluate(fill("'{{param.reviewed_by}}'", "x' + 'y"))).isEqualTo("x' + 'y");
    }

    @Test
    void aValueOutsideQuotesGoesInAsBefore() {
        assertThat(((Number) evaluate(fill("{{param.reviewed_by}} + 1", "41"))).doubleValue()).isEqualTo(42.0);
    }

    @Test
    void aQuoteWrittenTwiceInsideTextIsOneQuoteAndAnEmptyTextStaysEmpty() {
        assertThat(Expression.parse("'it''s'").evaluate(Collections.emptyMap())).isEqualTo("it's");
        assertThat(Expression.parse("\"a \"\"b\"\"\"").evaluate(Collections.emptyMap())).isEqualTo("a \"b\"");
        assertThat(Expression.parse("concat('', 'x', '')").evaluate(Collections.emptyMap())).isEqualTo("x");
        assertThat(Expression.parse("''''").evaluate(Collections.emptyMap())).isEqualTo("'");   // SQL's way: a text of one quote
    }
}
