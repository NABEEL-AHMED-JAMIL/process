package process.pipeline.backing;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A report as the lines media-service's streamed render reads (ADR-027; media's TableReport): written to a file in the
 * step's scratch directory as the rows are read, never held, then sent as the request body.
 * <pre>
 *   {"title": ..., "fields": {...}, "orientation": ..., "watermark": ..., "templateId": ..., "fileName": ...}   first
 *   {"section": {"title": ..., "note": ..., "columns": [...], "rows": N, "trailer": ...}}                      a table
 *   [v1, v2, ...]                                                                                              N rows
 * </pre>
 * The settings line is written last but sent first: {@link #head} may be called after the sections, when the widest
 * table (and so the page) is known. A row's values are text, numbers, true/false or null; anything else is its text.
 */
public final class ReportLines implements Closeable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFactory FACTORY = JSON.getFactory();

    private final Path body;
    private final JsonGenerator out;
    private byte[] head;
    private long expected;
    private long rows;
    private int sections;
    private boolean closed;

    public ReportLines(Path dir) throws IOException {
        this.body = Files.createTempFile(dir, "report-lines-", ".ndjson");
        OutputStream file = new BufferedOutputStream(Files.newOutputStream(this.body), 1 << 16);
        this.out = FACTORY.createGenerator(file, JsonEncoding.UTF8);
        this.out.setRootValueSeparator(new SerializedString("\n"));
    }

    /** The report's settings: written once, sent first. Values that are null are left out. */
    public void head(Map<String, Object> settings) throws IOException {
        Map<String, Object> present = new LinkedHashMap<>();
        settings.forEach((k, v) -> {
            if (v != null) {
                present.put(k, v);
            }
        });
        this.head = (JSON.writeValueAsString(present) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A table: its rows follow, exactly {@code rows} of them. A null title for the one table of a plain report. */
    public void section(String title, String note, List<String> columns, long rows, String trailer) throws IOException {
        this.requireRowsDone();
        this.out.writeStartObject();
        this.out.writeObjectFieldStart("section");
        this.text("title", title);
        this.text("note", note);
        this.out.writeArrayFieldStart("columns");
        for (String column : columns) {
            this.out.writeString(column);
        }
        this.out.writeEndArray();
        this.out.writeNumberField("rows", rows);
        this.text("trailer", trailer);
        this.out.writeEndObject();
        this.out.writeEndObject();
        this.expected = rows;
        this.rows = 0;
        this.sections++;
    }

    /** One row of the current table, its values in the table's column order. */
    public void row(List<Object> values) throws IOException {
        if (this.rows >= this.expected) {
            throw new IllegalStateException("The table already has the rows it declared.");
        }
        this.out.writeStartArray();
        for (Object value : values) {
            this.value(value);
        }
        this.out.writeEndArray();
        this.rows++;
    }

    /** One row of the current table from a row map, in {@code columns}' order. */
    public void row(Map<String, Object> row, List<String> columns) throws IOException {
        if (this.rows >= this.expected) {
            throw new IllegalStateException("The table already has the rows it declared.");
        }
        this.out.writeStartArray();
        for (String column : columns) {
            this.value(row.get(column));
        }
        this.out.writeEndArray();
        this.rows++;
    }

    public int sections() {
        return this.sections;
    }

    /** Bytes the request body will have: the settings line and every table. */
    public long size() throws IOException {
        this.finish();
        return this.head.length + Files.size(this.body);
    }

    /** Writes the body: the settings line, then the tables. */
    public void writeTo(OutputStream target) throws IOException {
        this.finish();
        target.write(this.head);
        Files.copy(this.body, target);
    }

    @Override
    public void close() throws IOException {
        try {
            this.finishQuietly();
        } finally {
            Files.deleteIfExists(this.body);
        }
    }

    private void finish() throws IOException {
        if (this.head == null) {
            throw new IllegalStateException("The report has no settings line.");
        }
        if (!this.closed) {
            this.requireRowsDone();
            this.out.close();
            this.closed = true;
        }
    }

    private void finishQuietly() throws IOException {
        if (!this.closed) {
            this.closed = true;
            this.out.close();
        }
    }

    private void requireRowsDone() {
        if (this.rows != this.expected) {
            throw new IllegalStateException(String.format("A table declared %d rows and has %d.", this.expected, this.rows));
        }
    }

    private void text(String field, String value) throws IOException {
        if (value != null && !value.trim().isEmpty()) {
            this.out.writeStringField(field, value);
        }
    }

    private void value(Object value) throws IOException {
        if (value == null) {
            this.out.writeNull();
        } else if (value instanceof Boolean) {
            this.out.writeBoolean((Boolean) value);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            this.out.writeNumber(((Number) value).longValue());
        } else if (value instanceof BigDecimal) {
            this.out.writeNumber((BigDecimal) value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                this.out.writeString(String.valueOf(d));
            } else {
                this.out.writeNumber(d);
            }
        } else if (value instanceof BigInteger) {
            this.out.writeNumber((BigInteger) value);
        } else {
            this.out.writeString(value.toString());
        }
    }
}
