package process.pipeline.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expression.mayBeDouble only skips Double.valueOf where it would certainly throw: for every text it says false to,
 * Double.valueOf refuses it, so a comparison answers exactly as before, without an exception per text cell.
 */
class ExpressionNumberGuardTest {

    private static boolean doubleAccepts(String text) {
        try {
            Double.valueOf(text);
            return true;
        } catch (NumberFormatException refused) {
            return false;
        }
    }

    @Test
    void everyTextDoubleAcceptsIsLetThrough() {
        List<String> texts = Arrays.asList("0", "-1", "+2.5", ".5", "5.", "1e5", "1E-3", "1.5f", "2D", "-3.0d", "NaN", "-Infinity",
            "Infinity", "0x1p3", "-0X1.8P1", "007", "1e+10", "north", "North", "Nope", "Inf", "2024-01-01", "", "+", "-", "e5",
            "1e", "1.2.3", "١٢", "1,000", "--5", "+-5", "f", "d", "0x", "12abc", "abc12", " 5 ".trim(), "1e5x", "5ff");
        for (String text : texts) {
            if (doubleAccepts(text)) {
                assertThat(Expression.mayBeDouble(text)).as(text).isTrue();
            }
        }
        assertThat(Expression.mayBeDouble("north")).isFalse();
        assertThat(Expression.mayBeDouble("2024-01-01")).isFalse();
        assertThat(Expression.mayBeDouble("North")).isFalse();
    }

    @Test
    void randomTextsAgreeWithDoubleValueOf() {
        Random random = new Random(42);
        String alphabet = "0123456789.+-eEfFdDxXpPNaIinty ab";
        for (int n = 0; n < 200_000; n++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(8);
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String t = text.toString().trim();
            if (doubleAccepts(t)) {
                assertThat(Expression.mayBeDouble(t)).as("'%s'", t).isTrue();
            }
        }
    }
}
