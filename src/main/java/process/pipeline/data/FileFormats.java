package process.pipeline.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SequenceWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import process.pipeline.Dataset;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;

/**
 * Rows to and from the files a pipeline reads and writes (MIG-231): CSV, JSON (an array of objects, or an object with
 * the rows at a path), JSON Lines, and Parquet (read only, through DuckDB -- {@link ParquetRows}).
 *
 * CSV values are read as text, never guessed at: "007" stays "007"; a Transform casts what must be a number. A CSV
 * written here has a header row, RFC 4180 quoting, UTF-8, and nulls as empty cells.
 */
public final class FileFormats {

    public static final List<String> READABLE = Collections.unmodifiableList(Arrays.asList("csv", "json", "jsonl", "parquet"));
    public static final List<String> WRITABLE = Collections.unmodifiableList(Arrays.asList("csv", "json", "jsonl"));

    private FileFormats() {
    }

    /** The format a key's extension says; null when it says none this reads. */
    public static String byExtension(String key) {
        String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "csv";
        }
        if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) {
            return "jsonl";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".parquet")) {
            return "parquet";
        }
        return null;
    }

    public static String contentType(String format) {
        switch (format) {
            case "csv":
                return "text/csv; charset=utf-8";
            case "json":
                return "application/json";
            case "jsonl":
                return "application/x-ndjson; charset=utf-8";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    /** Options a read takes: a CSV's delimiter and whether its first row names the columns; a JSON's rows path. */
    public static final class ReadOptions {
        public char delimiter = ',';
        public boolean header = true;
        public String rowsPath;
    }

    /** Reads a file's rows into the collector (held to its bounds as they come). */
    public static void read(byte[] content, String format, ReadOptions options, RowCollector rows) throws Exception {
        Limits.requireBytes(content.length, "The file");
        switch (format) {
            case "csv":
                csv(content, options, rows);
                break;
            case "json":
                json(content, options, rows);
                break;
            case "jsonl":
                jsonl(content, rows);
                break;
            case "parquet":
                ParquetRows.read(content, rows);
                break;
            default:
                throw new IllegalArgumentException("A file is read as csv, json, jsonl or parquet, not " + format + ".");
        }
    }

    /** Whether a format is read as a stream ({@link #readStream}); the others are read whole. */
    public static boolean streams(String format) {
        return "csv".equals(format) || "jsonl".equals(format);
    }

    /**
     * MIG-344: a CSV or JSON Lines file's rows, read from a stream into a sink as they come -- never the whole file in
     * memory. The same rows as {@link #read} makes (CSV cells as text, the BOM off the first column, empty lines
     * skipped). Stops after {@code maxRows} when the step asked for fewer (not a failure); fails past {@code limit} rows
     * or {@value Limits#MAX_COLUMNS} columns, naming {@code what}.
     */
    // CSVFormat's with* methods: the builder API reads the same; moving is Wave 6 maintainability, not a lint fix.
    @SuppressWarnings("deprecation")
    public static void readStream(InputStream content, String format, ReadOptions options, RowSink sink, Integer maxRows, long limit,
                                  String what) throws Exception {
        long wanted = maxRows == null ? Long.MAX_VALUE : maxRows;
        if ("csv".equals(format)) {
            CSVFormat csv = CSVFormat.RFC4180.withDelimiter(options.delimiter).withIgnoreEmptyLines();
            if (options.header) {
                csv = csv.withFirstRecordAsHeader();
            }
            try (Reader reader = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8), 1 << 16);
                 CSVParser parser = csv.parse(reader)) {
                List<String> header = options.header ? parser.getHeaderNames() : null;
                String[] names = null;
                if (header != null) {
                    names = new String[header.size()];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = stripBom(header.get(i));
                    }
                }
                for (CSVRecord record : parser) {
                    if (sink.size() >= wanted) {
                        return;
                    }
                    Map<String, Object> row = new LinkedHashMap<>(record.size() * 4 / 3 + 1);
                    for (int i = 0; i < record.size(); i++) {
                        String column = names != null && i < names.length ? names[i] : "c" + (i + 1);
                        row.put(column, record.get(i));
                    }
                    addBounded(sink, row, limit, what);
                }
            }
            return;
        }
        if ("jsonl".equals(format)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null && sink.size() < wanted) {
                    number++;
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    Map<String, Object> row;
                    try {
                        row = Values.row(Values.JSON.readTree(line));
                    } catch (IOException broken) {
                        throw new IllegalArgumentException(String.format("Line %d is not JSON.", number));
                    }
                    addBounded(sink, row, limit, what);
                }
            }
            return;
        }
        throw new IllegalArgumentException("Only csv and jsonl are read as a stream, not " + format + ".");
    }

    private static void addBounded(RowSink sink, Map<String, Object> row, long limit, String what) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("The read was stopped.");
        }
        if (sink.size() >= limit) {
            throw new IllegalStateException(String.format("%s has more than %,d rows, the most a step holds.", what, limit));
        }
        if (row.size() > Limits.MAX_COLUMNS) {
            throw new IllegalStateException(String.format("%s has %d columns; a step holds at most %d.", what, row.size(), Limits.MAX_COLUMNS));
        }
        sink.add(row);
    }

    // CSVFormat's with* methods: the builder API reads the same; moving is Wave 6 maintainability, not a lint fix.
    @SuppressWarnings("deprecation")
    private static void csv(byte[] content, ReadOptions options, RowCollector rows) throws IOException {
        CSVFormat format = CSVFormat.RFC4180.withDelimiter(options.delimiter).withIgnoreEmptyLines();
        if (options.header) {
            format = format.withFirstRecordAsHeader();
        }
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            List<String> header = options.header ? parser.getHeaderNames() : null;
            for (CSVRecord record : parser) {
                if (rows.full()) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < record.size(); i++) {
                    String column = header != null && i < header.size() ? header.get(i) : "c" + (i + 1);
                    row.put(stripBom(column), record.get(i));
                }
                rows.add(row);
            }
        }
    }

    private static String stripBom(String column) {
        return column != null && !column.isEmpty() && column.charAt(0) == '﻿' ? column.substring(1) : column;
    }

    private static void json(byte[] content, ReadOptions options, RowCollector rows) throws IOException {
        JsonNode root = Values.JSON.readTree(content);
        JsonNode at = Values.at(root, options.rowsPath);
        if (at == null || at.isMissingNode()) {
            throw new IllegalArgumentException("The file has nothing at " + options.rowsPath + ".");
        }
        each(at, rows);
    }

    /** An array's elements as rows; any other value as one row. */
    public static void each(JsonNode node, RowCollector rows) {
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (rows.full()) {
                    return;
                }
                rows.add(Values.row(element));
            }
        } else if (!node.isNull()) {
            rows.add(Values.row(node));
        }
    }

    private static void jsonl(byte[] content, RowCollector rows) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null && !rows.full()) {
                number++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    rows.add(Values.row(Values.JSON.readTree(line)));
                } catch (IOException broken) {
                    throw new IllegalArgumentException(String.format("Line %d is not JSON.", number));
                }
            }
        }
    }

    /** The dataset as a file in this format, at most {@link Limits#MAX_FILE_BYTES}. */
    public static byte[] write(Dataset dataset, String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeTo(dataset, format, bytes);
        Limits.requireBytes(bytes.size(), "The file");
        return bytes.toByteArray();
    }

    /**
     * The dataset in this format, straight to a stream -- a run dataset's download (Wave 4), which a step's file limit
     * does not bound: the dataset was already held to the row and cell limits when it was made. The stream is left open.
     */
    public static void writeTo(Dataset dataset, String format, OutputStream target) throws IOException {
        try {
            writeTo(RowSource.of(dataset), format, target);
        } catch (IOException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    /**
     * MIG-344: rows in this format, a batch at a time, straight to a stream (Save File, Upload, a dataset's download):
     * the same bytes {@link #writeTo(Dataset, String, OutputStream)} writes for the same rows. The stream is left open.
     * Returns the rows written.
     */
    // CSVFormat's with* methods: the builder API reads the same; moving is Wave 6 maintainability, not a lint fix.
    @SuppressWarnings("deprecation")
    public static long writeTo(RowSource rows, String format, OutputStream target) throws Exception {
        // Closing the CSV printer, or Jackson finishing a value, would close the target: the caller owns it.
        OutputStream bytes = new FilterOutputStream(target) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                this.out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                this.flush();
            }
        };
        List<String> columns = rows.columns();
        long written = 0;
        switch (format) {
            case "csv":
                try (Writer writer = new BufferedWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), 1 << 16);
                     CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180.withHeader(columns.toArray(new String[0])))) {
                    List<Object> cells = new ArrayList<>(columns.size());
                    for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                        for (Map<String, Object> row : batch) {
                            cells.clear();
                            for (String column : columns) {
                                cells.add(Values.text(row.get(column)));
                            }
                            printer.printRecord(cells);
                            written++;
                        }
                        stopIfInterrupted();
                    }
                }
                break;
            case "json":
                try (SequenceWriter array = Values.JSON.writer().writeValuesAsArray(new BufferedOutputStream(bytes, 1 << 16))) {
                    for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                        for (Map<String, Object> row : batch) {
                            array.write(ordered(row, columns));
                            written++;
                        }
                        stopIfInterrupted();
                    }
                }
                break;
            case "jsonl":
                for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                    for (Map<String, Object> row : batch) {
                        bytes.write(Values.JSON.writeValueAsBytes(ordered(row, columns)));
                        bytes.write('\n');
                        written++;
                    }
                    stopIfInterrupted();
                }
                break;
            default:
                throw new IllegalArgumentException("A file is written as csv, json or jsonl, not " + format + ".");
        }
        bytes.flush();
        return written;
    }

    private static void stopIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("The write was stopped.");
        }
    }

    /** A row with every column, in this column order. */
    private static Map<String, Object> ordered(Map<String, Object> row, List<String> columns) {
        Map<String, Object> out = new LinkedHashMap<>(columns.size() * 4 / 3 + 1);
        for (String column : columns) {
            out.put(column, row.get(column));
        }
        return out;
    }

}
