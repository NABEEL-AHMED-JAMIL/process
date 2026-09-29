package process.pipeline.data;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.row;

/** MIG-231: a step's rows are bounded as they come -- rows, columns, cells, bytes -- and values compare as a person expects. */
class RowBoundsTest {

    @Test
    void aReadFailsAtTheRowThatPassesABoundUnlessTheStepAskedForFewer() {
        RowCollector unbounded = new RowCollector("The file", null);
        for (int i = 0; i < Limits.MAX_ROWS; i++) {
            unbounded.add(row("n", i));
        }
        assertThatThrownBy(() -> unbounded.add(row("n", -1))).hasMessage("The file has more than 50,000 rows, the most a step holds.");

        RowCollector few = new RowCollector("The file", 2);
        few.add(row("n", 1));
        few.add(row("n", 2));
        assertThat(few.full()).isTrue();
        few.add(row("n", 3));
        assertThat(few.size()).isEqualTo(2);
    }

    @Test
    void columnsAndCellsAreBoundedToo() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i <= Limits.MAX_COLUMNS; i++) {
            wide.put("c" + i, i);
        }
        assertThatThrownBy(() -> new RowCollector("The API's answer", null).add(wide))
            .hasMessage("The API's answer has 201 columns; a step holds at most 200.");
        assertThatThrownBy(() -> Limits.requireShape(10_000, 101, "The join"))
            .hasMessage("The join has 1,010,000 cells (10,000 rows x 101 columns); a step holds at most 1,000,000.");
        assertThatThrownBy(() -> Limits.requireBytes(Limits.MAX_FILE_BYTES + 1, "The file")).hasMessageContaining("at most 52,428,800");
        assertThatThrownBy(() -> FileFormats.read(new byte[(int) Limits.MAX_FILE_BYTES + 1], "csv", new FileFormats.ReadOptions(),
            new RowCollector("x", null))).hasMessageContaining("The file is 52,428,801 bytes");
    }

    @Test
    void keysAndComparisonsMeetAsAPersonExpects() {
        assertThat(Values.key("1")).isEqualTo(Values.key(1L)).isEqualTo(Values.key(1.0));
        assertThat(Values.key("001")).isNotEqualTo(Values.key(1L));
        assertThat(Values.key(null)).isNull();
        assertThat(Values.compare("10", 9)).isPositive();
        assertThat(Values.compare("b", "a")).isPositive();
        assertThat(Values.compare(null, 1)).isNegative();
        assertThat(Values.text(12.50)).isEqualTo("12.5");
        assertThat(Values.plain(new java.math.BigDecimal("7.00"))).isEqualTo(7L);
    }

    @Test
    void writtenFilesReadBackAsTheSameRows() throws Exception {
        process.pipeline.Dataset rows = process.pipeline.Dataset.of(java.util.Arrays.asList(row("id", "1", "name", "Acme \"A\""),
            row("id", "2", "name", null)));
        RowCollector back = new RowCollector("x", null);
        FileFormats.read(FileFormats.write(rows, "csv"), "csv", new FileFormats.ReadOptions(), back);
        assertThat(back.toDataset().getRows()).containsExactly(row("id", "1", "name", "Acme \"A\""), row("id", "2", "name", ""));
        assertThat(new String(FileFormats.write(rows, "json"), StandardCharsets.UTF_8))
            .isEqualTo("[{\"id\":\"1\",\"name\":\"Acme \\\"A\\\"\"},{\"id\":\"2\",\"name\":null}]");
    }
}
