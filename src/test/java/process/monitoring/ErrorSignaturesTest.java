package process.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-355: errors grouped by pattern as log tools group lines, and each sorted into a kind. */
class ErrorSignaturesTest {

    @Test
    void whatVariesBetweenRunsBecomesAPlaceholder() {
        String a = ErrorSignatures.pattern("Step 2 <report> failed: The step's input has 1,000,000 rows x 8 columns; a step that holds its "
            + "whole input in memory takes at most 50,000 rows and 1,000,000 cells.");
        String b = ErrorSignatures.pattern("Step 3 <summary> failed after 2 tries: The step's input has 100,000 rows x 40 columns; a step that "
            + "holds its whole input in memory takes at most 50,000 rows and 1,000,000 cells.");
        assertThat(a).isEqualTo(b).startsWith("The step's input has <n> rows x <n> columns");
        assertThat(ErrorSignatures.id("render_pdf", a)).isEqualTo(ErrorSignatures.id("render_pdf", b)).hasSize(12);
        assertThat(ErrorSignatures.id("render_pdf", a)).isNotEqualTo(ErrorSignatures.id("aggregate", a));
    }

    @Test
    void idsQuotedValuesPathsAndAddressesAreNotTheError() {
        assertThat(ErrorSignatures.pattern("Rejected by Riverside reviewer (user 4641): a@b.example said 'no' about /data/in/x.csv "
            + "run 6f1c2a9e-1b2c-4d5e-8f90-123456789abc")).isEqualTo("Rejected by Riverside reviewer (user <n>): <email> said <value> about <path> "
            + "run <id>");
        assertThat(ErrorSignatures.pattern(null)).isEqualTo("(no message)");
        assertThat(ErrorSignatures.pattern("x".repeat(500))).hasSize(ErrorSignatures.MAX_PATTERN);
    }

    @Test
    void eachErrorHasAKind() {
        assertThat(ErrorSignatures.kind("Interrupt", "STALLED", null, "Job 2947 stopped reporting")).isEqualTo(ErrorSignatures.TIMED_OUT);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", true, "Step took too long")).isEqualTo(ErrorSignatures.TIMED_OUT);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", false, "media-service did not render the report (HTTP 413): too many rows"))
            .isEqualTo(ErrorSignatures.TOO_LARGE);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", false, "Row 719896: late_text is not a number (sum).")).isEqualTo(ErrorSignatures.DATA);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", false, "The input has no column no_such_column.")).isEqualTo(ErrorSignatures.DATA);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", false, "Rejected by Riverside Health reviewer (user 4641): wrong totals"))
            .isEqualTo(ErrorSignatures.REJECTED);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", false, "ai-service answered HTTP 503")).isEqualTo(ErrorSignatures.SERVICE);
        assertThat(ErrorSignatures.kind("Failed", "REFUSED", false, "x")).isEqualTo(ErrorSignatures.SETTINGS);
        assertThat(ErrorSignatures.kind("Failed", "OPERATOR", false, "closed")).isEqualTo(ErrorSignatures.OPERATOR);
        assertThat(ErrorSignatures.kind("Failed", "WORKER", false, "Something odd")).isEqualTo(ErrorSignatures.OTHER);
        assertThat(ErrorSignatures.label(ErrorSignatures.TOO_LARGE)).isEqualTo("Too large for memory or a limit");
    }
}
