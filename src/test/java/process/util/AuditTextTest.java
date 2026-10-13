package process.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Risk #83: one full stop at a sentence's end, whoever wrote the words; an ellipsis and a path keep their dots. */
class AuditTextTest {

    @Test
    void aDoubledFullStopAtASentencesEndIsMadeOne() {
        assertThat(AuditText.oneFullStop("Comment: Checked.. The run's results are now APPROVED.."))
            .isEqualTo("Comment: Checked. The run's results are now APPROVED.");
        assertThat(AuditText.oneFullStop("Reason: why?. Bulk decision x.")).isEqualTo("Reason: why? Bulk decision x.");
        assertThat(AuditText.oneFullStop("Stop!.")).isEqualTo("Stop!");
    }

    @Test
    void anEllipsisAPathAndANumberAreLeftAlone() {
        assertThat(AuditText.oneFullStop("Waiting... then done.")).isEqualTo("Waiting... then done.");
        assertThat(AuditText.oneFullStop("Read ../data/x.csv at 1.5 MB/s.")).isEqualTo("Read ../data/x.csv at 1.5 MB/s.");
        assertThat(AuditText.oneFullStop(null)).isNull();
        assertThat(AuditText.oneFullStop("no stop")).isEqualTo("no stop");
    }

    @Test
    void aSentenceEndsInExactlyOneMark() {
        assertThat(AuditText.sentence(" Checked. ")).isEqualTo("Checked.");
        assertThat(AuditText.sentence("Checked")).isEqualTo("Checked.");
        assertThat(AuditText.sentence("Sure?")).isEqualTo("Sure?");
        assertThat(AuditText.sentence(null)).isEqualTo("");
    }
}
