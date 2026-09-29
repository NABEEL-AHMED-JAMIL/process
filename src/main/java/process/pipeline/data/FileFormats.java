package process.pipeline.data;

import com.fasterxml.jackson.databind.JsonNode;
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
        switch (format) {
            case "csv":
                try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
                     CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180.withHeader(dataset.getColumns().toArray(new String[0])))) {
                    for (Map<String, Object> row : dataset.getRows()) {
                        List<Object> cells = new ArrayList<>(dataset.getColumns().size());
                        for (String column : dataset.getColumns()) {
                            cells.add(Values.text(row.get(column)));
                        }
                        printer.printRecord(cells);
                    }
                }
                break;
            case "json":
                Values.JSON.writeValue(bytes, ordered(dataset));
                break;
            case "jsonl":
                for (Map<String, Object> row : ordered(dataset)) {
                    bytes.write(Values.JSON.writeValueAsBytes(row));
                    bytes.write('\n');
                }
                break;
            default:
                throw new IllegalArgumentException("A file is written as csv, json or jsonl, not " + format + ".");
        }
        bytes.flush();
    }

    /** Rows with every column, in the dataset's column order. */
    private static List<Map<String, Object>> ordered(Dataset dataset) {
        List<Map<String, Object>> rows = new ArrayList<>(dataset.size());
        for (Map<String, Object> row : dataset.getRows()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String column : dataset.getColumns()) {
                out.put(column, row.get(column));
            }
            rows.add(out);
        }
        return rows;
    }
}
