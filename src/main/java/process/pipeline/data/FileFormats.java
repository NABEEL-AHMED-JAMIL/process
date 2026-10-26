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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.util.Collection;

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
            // convert_file's targets (a converted file in a run's outputs, or written to a bucket).
            case "txt":
                return "text/plain; charset=utf-8";
            case "md":
                return "text/markdown; charset=utf-8";
            case "html":
                return "text/html; charset=utf-8";
            case "rtf":
                return "application/rtf";
            case "png":
                return "image/png";
            case "jpg":
                return "image/jpeg";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odt":
                return "application/vnd.oasis.opendocument.text";
            case "ods":
                return "application/vnd.oasis.opendocument.spreadsheet";
            case "odp":
                return "application/vnd.oasis.opendocument.presentation";
            default:
                return "application/octet-stream";
        }
    }

    /** Options a read takes: a CSV's delimiter and whether its first row names the columns; a JSON's rows path. */
    public static final class ReadOptions {
        public char delimiter = ',';
        public boolean header = true;
        public String rowsPath;
        /**
         * MIG-365: only these columns, in this order (null: every column). A CSV's other cells are never made into values,
         * so a wide file read for a few columns holds and writes only those; a column the file does not have fails the read
         * and lists the ones it has (a CSV, or a Parquet file) or is empty (a JSON row without it).
         */
        public List<String> columns;
    }

    /** The cells of a CSV row to keep: each named column's index, in the order asked; every column when none is named. */
    static int[] picks(String[] names, List<String> wanted, String what) {
        if (wanted == null) {
            int[] all = new int[names.length];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        Map<String, Integer> at = new HashMap<>();
        for (int i = names.length - 1; i >= 0; i--) {
            at.put(names[i], i);
        }
        int[] picked = new int[wanted.size()];
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < picked.length; i++) {
            Integer index = at.get(wanted.get(i));
            if (index == null) {
                missing.add(wanted.get(i));
                picked[i] = -1;
            } else {
                picked[i] = index;
            }
        }
        if (!missing.isEmpty()) {
            List<String> has = Arrays.asList(names);
            throw new IllegalArgumentException(String.format("%s has no column %s; its columns are: %s%s.", what, String.join(", ", missing),
                String.join(", ", has.subList(0, Math.min(40, has.size()))), has.size() > 40 ? String.format(" (and %d more)", has.size() - 40) : ""));
        }
        return picked;
    }

    /** A JSON row cut to the named columns, in that order (a column the row lacks is empty); the row itself when none are named. */
    static Map<String, Object> pick(Map<String, Object> row, List<String> wanted) {
        if (wanted == null) {
            return row;
        }
        Map<String, Object> picked = new LinkedHashMap<>(wanted.size() * 4 / 3 + 1);
        for (String column : wanted) {
            picked.put(column, row.get(column));
        }
        return picked;
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
                jsonl(content, options, rows);
                break;
            case "parquet":
                ParquetRows.read(content, rows, options.columns);
                break;
            default:
                throw new IllegalArgumentException("A file is read as csv, json, jsonl or parquet, not " + format + ".");
        }
    }

    /**
     * Whether a format is read as a stream ({@link #readStream}); the others (JSON) are read whole. ZT-S3: Parquet streams too,
     * a row group at a time through DuckDB ({@link ParquetRows#stream}), so a large file is not held to the in-memory bound.
     */
    public static boolean streams(String format) {
        return "csv".equals(format) || "jsonl".equals(format) || "parquet".equals(format);
    }

    /**
     * MIG-344: a CSV or JSON Lines file's rows (ZT-S3: or a Parquet file's), read from a stream into a sink as they come --
     * never the whole file in memory. The same rows as {@link #read} makes (CSV cells as text, the BOM off the first column, empty lines
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
                int[] picked = null;
                String[] pickedNames = null;
                if (options.columns != null) {
                    // MIG-365: only the named columns become values; without a header they are c1, c2, ...
                    if (names != null) {
                        picked = picks(names, options.columns, what);
                    } else {
                        picked = new int[options.columns.size()];
                        for (int i = 0; i < picked.length; i++) {
                            picked[i] = headerless(options.columns.get(i), what);
                        }
                    }
                    pickedNames = options.columns.toArray(new String[0]);
                    sink.declare(options.columns);
                }
                for (CSVRecord record : parser) {
                    if (sink.size() >= wanted) {
                        return;
                    }
                    Map<String, Object> row;
                    if (picked != null) {
                        row = new LinkedHashMap<>(picked.length * 4 / 3 + 1);
                        for (int i = 0; i < picked.length; i++) {
                            row.put(pickedNames[i], picked[i] < record.size() ? record.get(picked[i]) : null);
                        }
                    } else {
                        row = new LinkedHashMap<>(record.size() * 4 / 3 + 1);
                        for (int i = 0; i < record.size(); i++) {
                            String column = names != null && i < names.length ? names[i] : "c" + (i + 1);
                            row.put(column, record.get(i));
                        }
                    }
                    addBounded(sink, row, limit, what);
                }
            }
            return;
        }
        if ("jsonl".equals(format)) {
            if (options.columns != null) {
                sink.declare(options.columns);
            }
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
                        row = pick(Values.row(Values.JSON.readTree(line)), options.columns);
                    } catch (IOException broken) {
                        throw new IllegalArgumentException(String.format("Line %d is not JSON.", number));
                    }
                    addBounded(sink, row, limit, what);
                }
            }
            return;
        }
        if ("parquet".equals(format)) {
            if (options.columns != null) {
                sink.declare(options.columns);
            }
            ParquetRows.stream(content, options.columns, row -> {
                if (sink.size() >= wanted) {
                    return false;
                }
                addBounded(sink, row, limit, what);
                return true;
            });
            return;
        }
        throw new IllegalArgumentException("Only csv, jsonl and parquet are read as a stream, not " + format + ".");
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
            int[] picked = null;
            if (options.columns != null) {
                picked = new int[options.columns.size()];
                if (header != null) {
                    String[] names = new String[header.size()];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = stripBom(header.get(i));
                    }
                    picked = picks(names, options.columns, "The file");
                } else {
                    for (int i = 0; i < picked.length; i++) {
                        picked[i] = headerless(options.columns.get(i), "The file");
                    }
                }
            }
            for (CSVRecord record : parser) {
                if (rows.full()) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                if (picked != null) {
                    for (int i = 0; i < picked.length; i++) {
                        row.put(options.columns.get(i), picked[i] < record.size() ? record.get(picked[i]) : null);
                    }
                } else {
                    for (int i = 0; i < record.size(); i++) {
                        String column = header != null && i < header.size() ? header.get(i) : "c" + (i + 1);
                        row.put(stripBom(column), record.get(i));
                    }
                }
                rows.add(row);
            }
        }
    }

    /** A headerless CSV's column by its name, c1, c2, ...: its index. */
    private static int headerless(String column, String what) {
        if (column != null && column.matches("c[1-9][0-9]{0,3}")) {
            return Integer.parseInt(column.substring(1)) - 1;
        }
        throw new IllegalArgumentException(String.format("%s has no header row, so its columns are c1, c2, ...; '%s' is not one.", what, column));
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
        if (options.columns == null) {
            each(at, rows);
        } else if (at.isArray()) {
            for (JsonNode element : at) {
                if (rows.full()) {
                    return;
                }
                rows.add(pick(Values.row(element), options.columns));
            }
        } else if (!at.isNull()) {
            rows.add(pick(Values.row(at), options.columns));
        }
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

    private static void jsonl(byte[] content, ReadOptions options, RowCollector rows) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null && !rows.full()) {
                number++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    rows.add(pick(Values.row(Values.JSON.readTree(line)), options.columns));
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

    /**
     * One row as a file of its own (Save File and Upload with "One file per row"): csv a header and the row, jsonl one
     * line, json the row's object itself (not a list of one). The columns in {@code json} that hold JSON text (an AI
     * answer's list) are written as the lists and objects they hold. The stream is left open.
     */
    @SuppressWarnings("deprecation")
    public static void writeRow(List<String> columns, Map<String, Object> row, String format, Collection<String> json,
        OutputStream target) throws Exception {
        Map<String, Object> ordered = ordered(row, columns);
        if (json != null) {
            for (String column : json) {
                if (ordered.containsKey(column)) {
                    ordered.put(column, Values.structured(ordered.get(column)));
                }
            }
        }
        switch (format) {
            case "csv": {
                Writer writer = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8));
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180.withHeader(columns.toArray(new String[0])));
                List<Object> cells = new ArrayList<>(columns.size());
                for (String column : columns) {
                    cells.add(Values.text(row.get(column)));
                }
                printer.printRecord(cells);
                printer.flush();
                writer.flush();
                break;
            }
            case "json":
                target.write(Values.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(ordered));
                target.write('\n');
                break;
            case "jsonl":
                target.write(Values.JSON.writeValueAsBytes(ordered));
                target.write('\n');
                break;
            default:
                throw new IllegalArgumentException("A file is written as csv, json or jsonl, not " + format + ".");
        }
        target.flush();
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
