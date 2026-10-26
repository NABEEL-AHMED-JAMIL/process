package process.pipeline.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIG-344: reading a CSV or JSON Lines file as a stream gives the rows reading it whole gives; writing rows streamed writes the same bytes. */
public class FileFormatsStreamTest {

    private static Dataset whole(String content, String format, FileFormats.ReadOptions options, Integer maxRows) throws Exception {
        RowCollector rows = new RowCollector("f.csv", maxRows);
        FileFormats.read(content.getBytes(StandardCharsets.UTF_8), format, options, rows);
        return rows.toDataset();
    }

    private static Dataset streamed(String content, String format, FileFormats.ReadOptions options, Integer maxRows) throws Exception {
        RowSink.Collecting sink = RowSink.collecting("f.csv");
        FileFormats.readStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), format, options, sink, maxRows,
            Long.MAX_VALUE, "f.csv");
        return sink.toDataset();
    }

    @Test
    void csvAndJsonLinesReadTheSameRowsStreamed() throws Exception {
        String csv = "﻿id,name,note\n1,Ada,\"a, quoted\nline\"\n\n2,Bø,\n3,\"\"\"x\"\"\",tail,extra\n";
        FileFormats.ReadOptions options = new FileFormats.ReadOptions();
        for (Integer max : Arrays.asList(null, 2)) {
            Dataset whole = whole(csv, "csv", options, max);
            Dataset streamed = streamed(csv, "csv", options, max);
            assertThat(streamed.getColumns()).isEqualTo(whole.getColumns());
            assertThat(streamed.getRows()).isEqualTo(whole.getRows());
        }
        FileFormats.ReadOptions headless = new FileFormats.ReadOptions();
        headless.header = false;
        headless.delimiter = ';';
        String plain = "1;a\n2;b;c\n";
        assertThat(streamed(plain, "csv", headless, null).getRows()).isEqualTo(whole(plain, "csv", headless, null).getRows());

        String jsonl = "{\"id\":1,\"tags\":[\"a\"]}\n\n{\"id\":2.5,\"name\":null}\n";
        assertThat(streamed(jsonl, "jsonl", options, null).getRows()).isEqualTo(whole(jsonl, "jsonl", options, null).getRows());
        assertThatThrownBy(() -> streamed("{\"id\":1}\nnot json\n", "jsonl", options, null)).hasMessage("Line 2 is not JSON.");
    }

    /** Counts the rows it is given and keeps the last. */
    static final class Counting implements RowSink {
        long count;
        Map<String, Object> last;

        @Override
        public void declare(Collection<String> columns) {
        }

        @Override
        public void add(Map<String, Object> row) {
            this.count++;
            this.last = row;
        }

        @Override
        public long size() {
            return this.count;
        }
    }

    /** A Parquet file of `rows` rows (id, ward, response_s), made by DuckDB. */
    public static byte[] parquet(Path dir, int rows) throws Exception {
        Path file = dir.resolve("p" + rows + ".parquet");
        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement sql = duck.createStatement()) {
            sql.execute("COPY (SELECT i AS id, 'W' || (i % 8) AS ward, i % 600 AS response_s FROM range(" + rows + ") t(i)) TO '"
                + file.toAbsolutePath().toString().replace("'", "''") + "' (FORMAT PARQUET, ROW_GROUP_SIZE 20000)");
        }
        return Files.readAllBytes(file);
    }

    /**
     * ZT-S3 (showcase job 36): a Parquet file streams, a row group at a time, as a CSV does -- past the in-memory bound of
     * {@value Limits#MAX_ROWS} rows that reading it whole stops at -- with the same rows, the named columns only, maxRows and
     * the step's row bound.
     */
    @Test
    void parquetStreamsPastTheInMemoryBound(@TempDir Path dir) throws Exception {
        assertThat(FileFormats.streams("parquet")).isTrue();
        byte[] big = parquet(dir, Limits.MAX_ROWS + 10_000);
        FileFormats.ReadOptions options = new FileFormats.ReadOptions();
        Counting sink = new Counting();   // a step's output, not a dataset in memory (a collecting sink stops at the in-memory bound)
        FileFormats.readStream(new ByteArrayInputStream(big), "parquet", options, sink, null, Long.MAX_VALUE, "p.parquet");
        assertThat(sink.size()).isEqualTo(Limits.MAX_ROWS + 10_000);
        assertThat(sink.last).isEqualTo(Map.of("id", 59_999L, "ward", "W7", "response_s", 599L));

        byte[] small = parquet(dir, 3);
        RowCollector whole = new RowCollector("p.parquet", null);
        FileFormats.read(small, "parquet", options, whole);
        RowSink.Collecting streamed = RowSink.collecting("p.parquet");
        FileFormats.readStream(new ByteArrayInputStream(small), "parquet", options, streamed, null, Long.MAX_VALUE, "p.parquet");
        assertThat(streamed.toDataset().getRows()).isEqualTo(whole.toDataset().getRows());

        FileFormats.ReadOptions two = new FileFormats.ReadOptions();
        two.columns = Arrays.asList("response_s", "id");
        RowSink.Collecting picked = RowSink.collecting("p.parquet");
        FileFormats.readStream(new ByteArrayInputStream(big), "parquet", two, picked, 5, Long.MAX_VALUE, "p.parquet");
        assertThat(picked.toDataset().getColumns()).containsExactly("response_s", "id");
        assertThat(picked.toDataset().size()).isEqualTo(5);

        assertThatThrownBy(() -> FileFormats.readStream(new ByteArrayInputStream(big), "parquet", options, new Counting(), null, 100,
            "in/p.parquet")).hasMessage("in/p.parquet has more than 100 rows, the most a step holds.");
    }

    @Test
    void aStreamedReadStopsAtItsBound() {
        FileFormats.ReadOptions options = new FileFormats.ReadOptions();
        RowSink.Collecting sink = RowSink.collecting("f.csv");
        assertThatThrownBy(() -> FileFormats.readStream(new ByteArrayInputStream("a\n1\n2\n3\n".getBytes(StandardCharsets.UTF_8)), "csv",
            options, sink, null, 2, "in/f.csv")).hasMessage("in/f.csv has more than 2 rows, the most a step holds.");
    }

    @Test
    void rowsWrittenStreamedAreTheBytesTheDatasetWrites() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", 1);
        a.put("v", 2.50);
        a.put("t", "x,\"y\"");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("t", null);
        b.put("id", 2L);
        b.put("nested", Arrays.asList(1, "z"));
        Dataset dataset = new Dataset(Arrays.asList("id", "t", "v"), Arrays.asList(a, b));
        for (String format : FileFormats.WRITABLE) {
            ByteArrayOutputStream streamed = new ByteArrayOutputStream();
            FileFormats.writeTo(RowSource.of(dataset, 1), format, streamed);
            assertThat(streamed.toByteArray()).as(format).isEqualTo(FileFormats.write(dataset, format));
        }
        assertThat(new String(FileFormats.write(dataset, "json"), StandardCharsets.UTF_8))
            .isEqualTo("[{\"id\":1,\"t\":\"x,\\\"y\\\"\",\"v\":2.5},{\"id\":2,\"t\":null,\"v\":null}]");
    }
}
