package process.customer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIG-426: the format a client app chooses for a run's result -- format= first, then Accept, json by default. */
class ResultFormatTest {

    @Test
    void formatNamesItAnyCaseAndJsonIsTheDefault() {
        assertThat(ResultFormat.choose(null, null)).isEqualTo(ResultFormat.JSON);
        assertThat(ResultFormat.choose("", "  ")).isEqualTo(ResultFormat.JSON);
        assertThat(ResultFormat.choose("md", null)).isEqualTo(ResultFormat.MD);
        assertThat(ResultFormat.choose("PDF", null)).isEqualTo(ResultFormat.PDF);
        assertThat(ResultFormat.choose(" html ", null)).isEqualTo(ResultFormat.HTML);
        assertThat(ResultFormat.choose("json", "application/pdf")).as("format= wins over Accept").isEqualTo(ResultFormat.JSON);
    }

    @Test
    void anUnsupportedFormatNamesTheAllowedOnes() {
        assertThatThrownBy(() -> ResultFormat.choose("xlsx", null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("format is one of json, pdf, html, md.");
        assertThatThrownBy(() -> ResultFormat.choose("docx", "application/json")).hasMessage("format is one of json, pdf, html, md.");
        assertThatThrownBy(() -> ResultFormat.choose("markdown", null)).hasMessage("format is one of json, pdf, html, md.");
    }

    @Test
    void acceptIsReadByItsWeightsThenItsOrder() {
        assertThat(ResultFormat.choose(null, "text/markdown")).isEqualTo(ResultFormat.MD);
        assertThat(ResultFormat.choose(null, "text/x-markdown")).isEqualTo(ResultFormat.MD);
        assertThat(ResultFormat.choose(null, "application/pdf")).isEqualTo(ResultFormat.PDF);
        assertThat(ResultFormat.choose(null, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")).isEqualTo(ResultFormat.HTML);
        assertThat(ResultFormat.choose(null, "application/json, text/plain, */*")).isEqualTo(ResultFormat.JSON);
        assertThat(ResultFormat.choose(null, "*/*")).isEqualTo(ResultFormat.JSON);
        assertThat(ResultFormat.choose(null, "text/markdown;q=0.5, application/pdf")).isEqualTo(ResultFormat.PDF);
        assertThat(ResultFormat.choose(null, "application/pdf;q=0, text/html")).as("q=0 is never").isEqualTo(ResultFormat.HTML);
        assertThat(ResultFormat.choose(null, "Text/Markdown; charset=utf-8")).isEqualTo(ResultFormat.MD);
    }

    @Test
    void anAcceptNamingNothingTheResultIsGivenInIsRefused() {
        assertThatThrownBy(() -> ResultFormat.choose(null, "text/plain")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("application/json, application/pdf, text/html or text/markdown").hasMessageContaining("format=json|pdf|html|md");
        assertThatThrownBy(() -> ResultFormat.choose(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResultFormat.choose(null, "application/pdf;q=0")).isInstanceOf(IllegalArgumentException.class);
    }
}
