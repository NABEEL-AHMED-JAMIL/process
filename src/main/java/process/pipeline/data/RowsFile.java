package process.pipeline.data;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;

/**
 * A dataset on disk as Core's own row file (MIG-344, ".rows"): written one row at a time, read back one batch at a
 * time, so neither side ever holds the table.
 *
 * <b>Exactly what the JSON store gives back.</b> A row read from here equals the row the JSON dataset store
 * ({@code FileDatasetStore}'s ".json") would have read back: the same keys in the same order (a key a row lacks stays
 * lacking, null stays null), and the same value types -- text, true/false, Integer for a whole number that fits an int
 * (a Long that fits one too, as Jackson reads it), Long, Double, and anything else (BigDecimal, Float, nested maps and
 * lists, NaN) through Jackson itself. So a step reads the same values whichever store wrote its input.
 *
 * <b>Layout.</b> "ETLROWS1"; then records -- 1: a key (its name; keys are numbered in order), 2: a row (its entry count,
 * then key number and value per entry), 0: the end; then the footer -- the row count and the dataset's columns -- and
 * the footer's offset in the last 8 bytes. Lengths and numbers are varints (zig-zag for signed). No compression: the
 * file is about as big as the CSV it came from.
 *
 * A writer writes to "{name}.{random}.partial" beside its target and moves it into place on {@link Writer#commit}, so a
 * failed or timed-out try leaves no half dataset (the sweep removes partial files a crash left).
 */
public final class RowsFile {

    static final byte[] MAGIC = "ETLROWS1".getBytes(StandardCharsets.US_ASCII);
    static final ObjectMapper JSON = new ObjectMapper();

    private static final int END = 0;
    private static final int KEY = 1;
    private static final int ROW = 2;

    static final int NULL = 0;
    static final int STRING = 1;
    static final int TRUE = 2;
    static final int FALSE = 3;
    static final int INT = 4;
    static final int LONG = 5;
    static final int DOUBLE = 6;
    static final int JSON_VALUE = 7;

    private RowsFile() {
    }

    public static boolean isRowsKey(String storageKey) {
        return storageKey != null && storageKey.endsWith(".rows");
    }

    /** A writer to {@code target} (written on commit). */
    public static Writer create(Path target) throws IOException {
        return new Writer(target);
    }

    /** The file's rows, a batch at a time. */
    public static Reader open(Path file, int batch) throws IOException {
        return new Reader(file, batch);
    }

    // ------------------------------------------------------------------------------------------------------------ writer

    public static final class Writer implements RowSink, AutoCloseable {
        private final Path target;
        private final Path partial;
        private final OutputStream out;
        private final byte[] buffer = new byte[1 << 16];
        private int at;
        private long written;
        private final Map<String, Integer> keys = new HashMap<>();
        private List<String> declared;
        private final Set<String> seen = new LinkedHashSet<>();
        private long rows;
        private boolean done;

        Writer(Path target) throws IOException {
            this.target = target;
            Files.createDirectories(target.getParent());
            this.partial = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8) + ".partial");
            this.out = Files.newOutputStream(this.partial);
            this.raw(MAGIC, 0, MAGIC.length);
        }

        @Override
        public void declare(Collection<String> columns) {
            this.declared = new ArrayList<>(columns);
        }

        @Override
        public void add(Map<String, Object> row) throws IOException {
            if (this.done) {
                throw new IllegalStateException("This dataset file is already written.");
            }
            // Keys first: a row's keys are numbered before the row names them.
            for (String key : row.keySet()) {
                if (!this.keys.containsKey(key)) {
                    this.keys.put(key, this.keys.size());
                    this.byte1(KEY);
                    this.string(key);
                }
            }
            if (this.declared == null) {
                this.seen.addAll(row.keySet());
            }
            this.byte1(ROW);
            this.varint(row.size());
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                this.varint(this.keys.get(entry.getKey()));
                this.value(entry.getValue());
            }
            this.rows++;
        }

        @Override
        public long size() {
            return this.rows;
        }

        /** The columns the file will say: the declared ones, else every key seen. */
        public List<String> columns() {
            return this.declared != null ? this.declared : new ArrayList<>(this.seen);
        }

        /** Bytes written so far. */
        public long bytes() {
            return this.written + this.at;
        }

        /** Writes the footer and moves the file into place. Returns its size in bytes. */
        public long commit() throws IOException {
            if (this.done) {
                throw new IllegalStateException("This dataset file is already written.");
            }
            this.byte1(END);
            long footer = this.bytes();
            this.varlong(this.rows);
            List<String> columns = this.columns();
            this.varint(columns.size());
            for (String column : columns) {
                this.string(column);
            }
            this.ensure(8);
            for (int shift = 56; shift >= 0; shift -= 8) {
                this.buffer[this.at++] = (byte) (footer >>> shift);
            }
            this.flush();
            this.out.close();
            this.done = true;
            Files.move(this.partial, this.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Files.size(this.target);
        }

        /** Drops what was written: nothing is left behind. Safe to call after commit (it does nothing then). */
        public void abort() {
            if (this.done) {
                return;
            }
            this.done = true;
            try {
                this.out.close();
            } catch (IOException ignored) {
                // closing a file we are deleting
            }
            try {
                Files.deleteIfExists(this.partial);
            } catch (IOException ignored) {
                // the sweep removes partial files left behind
            }
        }

        @Override
        public void close() {
            this.abort();
        }

        private void value(Object value) throws IOException {
            if (value == null) {
                this.byte1(NULL);
            } else if (value instanceof String) {
                this.byte1(STRING);
                this.string((String) value);
            } else if (value instanceof Boolean) {
                this.byte1((Boolean) value ? TRUE : FALSE);
            } else if (value instanceof Integer) {
                this.byte1(INT);
                this.varlong(zigzag((Integer) value));
            } else if (value instanceof Long) {
                long v = (Long) value;
                // Jackson reads a whole number that fits an int back as an Integer: so does this file.
                this.byte1(v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? INT : LONG);
                this.varlong(zigzag(v));
            } else if (value instanceof Double && Double.isFinite((Double) value)) {
                this.byte1(DOUBLE);
                long bits = Double.doubleToRawLongBits((Double) value);
                this.ensure(8);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    this.buffer[this.at++] = (byte) (bits >>> shift);
                }
            } else {
                // BigDecimal, Float, Short, nested maps and lists, NaN...: Jackson's own bytes, read back by Jackson.
                byte[] json = JSON.writeValueAsBytes(value);
                this.byte1(JSON_VALUE);
                this.varint(json.length);
                this.raw(json, 0, json.length);
            }
        }

        private void string(String text) throws IOException {
            int length = text.length();
            boolean ascii = true;
            for (int i = 0; i < length; i++) {
                if (text.charAt(i) >= 0x80) {
                    ascii = false;
                    break;
                }
            }
            if (ascii && length <= this.buffer.length) {
                this.varint(length);
                this.ensure(length);
                for (int i = 0; i < length; i++) {
                    this.buffer[this.at++] = (byte) text.charAt(i);
                }
                return;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            this.varint(bytes.length);
            this.raw(bytes, 0, bytes.length);
        }

        private void byte1(int b) throws IOException {
            this.ensure(1);
            this.buffer[this.at++] = (byte) b;
        }

        private void varint(int value) throws IOException {
            this.varlong(value & 0xFFFFFFFFL);
        }

        private void varlong(long value) throws IOException {
            this.ensure(10);
            while ((value & ~0x7FL) != 0) {
                this.buffer[this.at++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            this.buffer[this.at++] = (byte) value;
        }

        private void raw(byte[] bytes, int offset, int length) throws IOException {
            if (length > this.buffer.length) {
                this.flush();
                this.out.write(bytes, offset, length);
                this.written += length;
                return;
            }
            this.ensure(length);
            System.arraycopy(bytes, offset, this.buffer, this.at, length);
            this.at += length;
        }

        private void ensure(int bytes) throws IOException {
            if (this.at + bytes > this.buffer.length) {
                this.flush();
                if (bytes > this.buffer.length) {
                    throw new IllegalStateException("A value is larger than the write buffer.");
                }
            }
        }

        private void flush() throws IOException {
            if (this.at > 0) {
                this.out.write(this.buffer, 0, this.at);
                this.written += this.at;
                this.at = 0;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ reader

    public static final class Reader implements RowSource {
        private final InputStream in;
        private final int batch;
        private final long rows;
        private final List<String> columns;
        private final List<String> keys = new ArrayList<>();
        private final byte[] buffer = new byte[1 << 16];
        private int at;
        private int end;
        private boolean finished;

        Reader(Path file, int batch) throws IOException {
            this.batch = Math.max(1, batch);
            long footer;
            try (RandomAccessFile random = new RandomAccessFile(file.toFile(), "r")) {
                long length = random.length();
                if (length < MAGIC.length + 9) {
                    throw new IOException("Not a dataset file: " + file.getFileName());
                }
                byte[] magic = new byte[MAGIC.length];
                random.readFully(magic);
                if (!Arrays.equals(magic, MAGIC)) {
                    throw new IOException("Not a dataset file: " + file.getFileName());
                }
                random.seek(length - 8);
                footer = random.readLong();
                byte[] tail = new byte[(int) (length - 8 - footer)];
                random.seek(footer);
                random.readFully(tail);
                Cursor cursor = new Cursor(tail);
                this.rows = cursor.varlong();
                int count = (int) cursor.varlong();
                List<String> names = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    names.add(cursor.string());
                }
                this.columns = Collections.unmodifiableList(names);
            }
            this.in = Files.newInputStream(file);
            this.skip(MAGIC.length);
        }

        @Override
        public List<String> columns() {
            return this.columns;
        }

        @Override
        public long size() {
            return this.rows;
        }

        @Override
        public List<Map<String, Object>> next() throws IOException {
            if (this.finished) {
                return null;
            }
            List<Map<String, Object>> out = new ArrayList<>(Math.min(this.batch, (int) Math.min(Integer.MAX_VALUE, Math.max(1, this.rows))));
            while (out.size() < this.batch) {
                int type = this.byte1();
                if (type == END) {
                    this.finished = true;
                    break;
                }
                if (type == KEY) {
                    this.keys.add(this.string());
                    continue;
                }
                if (type != ROW) {
                    throw new IOException("A dataset file is damaged (record " + type + ").");
                }
                int n = (int) this.varlong();
                Map<String, Object> row = new LinkedHashMap<>(n * 4 / 3 + 1);
                for (int i = 0; i < n; i++) {
                    String key = this.keys.get((int) this.varlong());
                    row.put(key, this.value());
                }
                out.add(row);
            }
            return out.isEmpty() ? null : out;
        }

        @Override
        public void close() throws IOException {
            this.in.close();
        }

        private Object value() throws IOException {
            int type = this.byte1();
            switch (type) {
                case NULL:
                    return null;
                case STRING:
                    return this.string();
                case TRUE:
                    return Boolean.TRUE;
                case FALSE:
                    return Boolean.FALSE;
                case INT:
                    return (int) unzigzag(this.varlong());
                case LONG:
                    return unzigzag(this.varlong());
                case DOUBLE: {
                    this.fill(8);
                    long bits = 0;
                    for (int i = 0; i < 8; i++) {
                        bits = (bits << 8) | (this.buffer[this.at++] & 0xFF);
                    }
                    return Double.longBitsToDouble(bits);
                }
                case JSON_VALUE: {
                    byte[] json = this.bytes((int) this.varlong());
                    return JSON.readValue(json, Object.class);
                }
                default:
                    throw new IOException("A dataset file is damaged (value " + type + ").");
            }
        }

        private String string() throws IOException {
            int length = (int) this.varlong();
            if (length <= this.buffer.length) {
                this.fill(length);
                String text = new String(this.buffer, this.at, length, StandardCharsets.UTF_8);
                this.at += length;
                return text;
            }
            return new String(this.bytes(length), StandardCharsets.UTF_8);
        }

        private byte[] bytes(int length) throws IOException {
            byte[] out = new byte[length];
            int copied = 0;
            while (copied < length) {
                if (this.at == this.end) {
                    this.refill();
                }
                int n = Math.min(length - copied, this.end - this.at);
                System.arraycopy(this.buffer, this.at, out, copied, n);
                this.at += n;
                copied += n;
            }
            return out;
        }

        private int byte1() throws IOException {
            if (this.at == this.end) {
                this.refill();
            }
            return this.buffer[this.at++] & 0xFF;
        }

        private long varlong() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = this.byte1();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("A dataset file is damaged (number).");
        }

        private void skip(int n) throws IOException {
            this.fill(n);
            this.at += n;
        }

        /** At least {@code n} bytes in the buffer from {@code at}. */
        private void fill(int n) throws IOException {
            if (this.end - this.at >= n) {
                return;
            }
            System.arraycopy(this.buffer, this.at, this.buffer, 0, this.end - this.at);
            this.end -= this.at;
            this.at = 0;
            while (this.end < n) {
                int read = this.in.read(this.buffer, this.end, this.buffer.length - this.end);
                if (read < 0) {
                    throw new EOFException("A dataset file ends early.");
                }
                this.end += read;
            }
        }

        private void refill() throws IOException {
            this.at = 0;
            this.end = 0;
            int read = this.in.read(this.buffer, 0, this.buffer.length);
            if (read < 0) {
                throw new EOFException("A dataset file ends early.");
            }
            this.end = read;
        }
    }

    /** Reads the footer's bytes. */
    private static final class Cursor {
        private final byte[] bytes;
        private int at;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        long varlong() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (this.at >= this.bytes.length) {
                    throw new EOFException("A dataset file's footer ends early.");
                }
                int b = this.bytes[this.at++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("A dataset file's footer is damaged.");
        }

        String string() throws IOException {
            int length = (int) this.varlong();
            String text = new String(this.bytes, this.at, length, StandardCharsets.UTF_8);
            this.at += length;
            return text;
        }
    }

    static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
