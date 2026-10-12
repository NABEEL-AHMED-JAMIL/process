package process.pipeline.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-365: read_file's {@code columns} -- only the named columns, in that order, read the same whole or streamed, for CSV
 * (with or without a header), JSON, JSON Lines and Parquet (projected by DuckDB); a column the file lacks is refused with
 * the ones it has (a JSON row without it is empty).
 */
class FileFormatsColumnsTest {

    @TempDir
    Path dir;

    private static FileFormats.ReadOptions only(String... columns) {
        FileFormats.ReadOptions options = new FileFormats.ReadOptions();
        options.columns = Arrays.asList(columns);
        return options;
    }

    private static Dataset whole(String content, String format, FileFormats.ReadOptions options) throws Exception {
        RowCollector rows = new RowCollector("f." + format, null);
        FileFormats.read(content.getBytes(StandardCharsets.UTF_8), format, options, rows);
        return rows.toDataset();
    }

    private static Dataset streamed(String content, String format, FileFormats.ReadOptions options) throws Exception {
        RowSink.Collecting sink = RowSink.collecting("f." + format);
        FileFormats.readStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), format, options, sink, null,
            Limits.MAX_STREAM_ROWS, "f." + format);
        return sink.toDataset();
    }

    private static final String CSV = "﻿id,name,age,ward\n1,Ana,61,B\n2,Ben,70,A\n3,Cy,,B\n";

    @Test
    void aCsvGivesOnlyTheNamedColumnsInTheirOrderWholeOrStreamed() throws Exception {
        for (Dataset read : Arrays.asList(whole(CSV, "csv", only("ward", "id")), streamed(CSV, "csv", only("ward", "id")))) {
            assertThat(read.getColumns()).containsExactly("ward", "id");
            assertThat(read.getRows()).extracting(r -> r.get("ward") + "/" + r.get("id")).containsExactly("B/1", "A/2", "B/3");
            assertThat(read.getRows().get(0)).containsOnlyKeys("ward", "id");
        }
        // The first column with its BOM is named without it.
        assertThat(streamed(CSV, "csv", only("id")).getRows()).extracting(r -> r.get("id")).containsExactly("1", "2", "3");
    }

    @Test
    void aColumnTheCsvLacksIsRefusedWithTheOnesItHas() {
        assertThatThrownBy(() -> streamed(CSV, "csv", only("id", "bmi", "sex")))
            .hasMessage("f.csv has no column bmi, sex; its columns are: id, name, age, ward.");
        assertThatThrownBy(() -> whole(CSV, "csv", only("bmi"))).hasMessage("The file has no column bmi; its columns are: id, name, age, ward.");
    }

    @Test
    void aCsvWithoutAHeaderNamesItsColumnsC1C2() throws Exception {
        FileFormats.ReadOptions options = only("c3", "c1");
        options.header = false;
        Dataset read = streamed("1,Ana,61\n2,Ben\n", "csv", options);
        assertThat(read.getRows()).extracting(r -> r.get("c3") + "/" + r.get("c1")).containsExactly("61/1", "null/2");
        FileFormats.ReadOptions wrong = only("age");
        wrong.header = false;
        assertThatThrownBy(() -> streamed("1,2\n", "csv", wrong)).hasMessageContaining("has no header row, so its columns are c1, c2");
    }

    @Test
    void jsonRowsAreCutToTheNamedColumnsAndAMissingOneIsEmpty() throws Exception {
        String lines = "{\"id\":1,\"labs\":[1,2],\"name\":\"Ana\"}\n{\"id\":2,\"name\":\"Ben\"}\n";
        for (Dataset read : Arrays.asList(whole(lines, "jsonl", only("name", "labs")), streamed(lines, "jsonl", only("name", "labs")))) {
            assertThat(read.getColumns()).containsExactly("name", "labs");
            assertThat(read.getRows()).extracting(r -> r.get("name") + "/" + r.get("labs")).containsExactly("Ana/[1,2]", "Ben/null");
        }
        Dataset array = whole("[{\"a\":1,\"b\":2,\"c\":3}]", "json", only("c", "a"));
        assertThat(array.getColumns()).containsExactly("c", "a");
    }

    @Test
    void parquetIsProjectedByDuckDb() throws Exception {
        Path file = this.dir.resolve("p.parquet");
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement sql = duck.createStatement()) {
            sql.execute("COPY (SELECT 1 AS id, 'Ana' AS name, 61 AS age UNION ALL SELECT 2, 'Ben', 70) TO '"
                + file.toAbsolutePath().toString().replace("'", "''") + "' (FORMAT PARQUET)");
        }
        byte[] content = Files.readAllBytes(file);
        RowCollector rows = new RowCollector("p.parquet", null);
        ParquetRows.read(content, rows, Arrays.asList("age", "id"));
        Dataset read = rows.toDataset();
        assertThat(read.getColumns()).containsExactly("age", "id");
        assertThat(read.getRows()).extracting(r -> r.get("age") + "/" + r.get("id")).containsExactly("61/1", "70/2");
        RowCollector none = new RowCollector("p.parquet", null);
        assertThatThrownBy(() -> ParquetRows.read(content, none, Collections.singletonList("bmi")))
            .hasMessage("The file has no column bmi; its columns are: id, name, age.");
        List<String> all = new ArrayList<>();
        RowCollector every = new RowCollector("p.parquet", null);
        ParquetRows.read(content, every, null);
        all.addAll(every.toDataset().getColumns());
        assertThat(all).containsExactly("id", "name", "age");
    }
}
