package process.pipeline.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-344: Values.number answers text that is not a number without throwing, and exactly as BigDecimal would. */
class ValuesNumberTest {

    /** What Values.number answered before MIG-344: BigDecimal's verdict. */
    private static BigDecimal old(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    @Test
    void theSameVerdictAsBigDecimalOnEveryEdge() {
        List<String> cases = new ArrayList<>(Arrays.asList("0", "-0", "+0", "1", "007", "1.", ".5", "-.5", "+.5", ".", "-", "+", "", " 12 ",
            "1e5", "1E5", "1e+5", "1e-5", "1.e5", ".5e1", "1e", "1e+", "e5", "1.2.3", "1,5", "1_000", "0x10", "NaN", "Infinity", "-Infinity",
            "1e99999999999", "1e2147483648", "١٢٣", "１２３", "12a", "a12", "1 2", "--1", "+-1", "1e5.0", "1e5e5", "2026-01-24", "CUST-000123",
            "north", "338.67", "-1.3598071336738", "12.030000000000001", "1E-400", "9".repeat(400)));
        Random random = new Random(344);
        String alphabet = "0123456789.+-eE x١";
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            for (int c = random.nextInt(7); c > 0; c--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            cases.add(text.toString());
        }
        for (String text : cases) {
            assertThat(Values.number(text)).as("'%s'", text).isEqualTo(old(text));
        }
    }
}
