package process.pipeline.tasks;

import process.pipeline.data.RunMemory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A statistics section of render_pdf (ADR-027): a profile of each column, computed as the rows stream past -- never over
 * the table held in memory -- so it covers every row of a 5-million-row input in the memory of a batch and a few small
 * structures per column.
 *
 * Per column: rows, empty values (null or blank), distinct values, min and max, and for a numeric column (every value a
 * number) the sum and the mean, exactly (BigDecimal: no rounding until the mean is printed); the most frequent values.
 * Distinct values and their counts are exact up to {@link #EXACT_DISTINCT} per column, held against the run's memory
 * budget; past that (or when the budget says no) distinct becomes a HyperLogLog estimate (about 1% error) and the most
 * frequent values the Misra-Gries heavy hitters, each marked as an estimate in the table. A histogram of a numeric column
 * needs its min and max first, so it is a second pass ({@link Histogram}).
 */
final class ReportStatistics {

    static final int EXACT_DISTINCT = 10_000;
    /** Heavy-hitter counters per column once the exact counts are dropped. */
    static final int HEAVY_HITTERS = 200;
    static final List<String> HEADER = Arrays.asList("column", "rows", "empty", "distinct", "min", "max", "sum", "mean", "most frequent");

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?");
    /** Bytes a distinct value costs in the exact counts, beyond its text: the map entry and its counter. */
    private static final long ENTRY_BYTES = 96;

    private final List<Column> columns = new ArrayList<>();
    private final RunMemory memory;
    private final int top;
    private long held;

    ReportStatistics(List<String> names, RunMemory memory, int top) {
        for (String name : names) {
            this.columns.add(new Column(name));
        }
        this.memory = memory;
        this.top = Math.max(0, top);
    }

    void add(Map<String, Object> row) {
        for (Column column : this.columns) {
            column.add(row.get(column.name));
        }
    }

    /** The section's rows, one per column, in {@link #HEADER}'s order. */
    List<List<Object>> rows() {
        List<List<Object>> out = new ArrayList<>();
        for (Column column : this.columns) {
            out.add(column.row());
        }
        return out;
    }

    Column column(String name) {
        for (Column column : this.columns) {
            if (column.name.equals(name)) {
                return column;
            }
        }
        return null;
    }

    /** Gives the run's budget back. */
    void release() {
        this.memory.release(this.held);
        this.held = 0;
    }

    final class Column {
        final String name;
        long rows;
        long empty;
        boolean numeric = true;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;
        String minText;
        String maxText;
        /** Exact counts per value, until there are too many; then null. */
        Map<String, long[]> counts = new HashMap<>();
        HyperLogLog distinct;
        Map<String, long[]> heavy;
        long heavyDecrements;

        Column(String name) {
            this.name = name;
        }

        void add(Object value) {
            this.rows++;
            String text = value == null ? null : value.toString();
            if (text == null || text.trim().isEmpty()) {
                this.empty++;
                return;
            }
            if (this.minText == null || text.compareTo(this.minText) < 0) {
                this.minText = text;
            }
            if (this.maxText == null || text.compareTo(this.maxText) > 0) {
                this.maxText = text;
            }
            if (this.numeric) {
                BigDecimal number = number(value, text);
                if (number == null) {
                    this.numeric = false;
                } else {
                    this.sum = this.sum.add(number);
                    this.min = this.min == null || number.compareTo(this.min) < 0 ? number : this.min;
                    this.max = this.max == null || number.compareTo(this.max) > 0 ? number : this.max;
                }
            }
            this.count(text);
        }

        private void count(String text) {
            if (this.counts != null) {
                long[] seen = this.counts.get(text);
                if (seen != null) {
                    seen[0]++;
                    return;
                }
                long cost = ENTRY_BYTES + 2L * text.length();
                if (this.counts.size() < EXACT_DISTINCT && ReportStatistics.this.memory.tryReserve(cost)) {
                    ReportStatistics.this.held += cost;
                    this.counts.put(text, new long[] {1});
                    return;
                }
                this.estimate();
            }
            this.distinct.add(text);
            this.hit(text);
        }

        /** Too many distinct values to count exactly: an estimate from here on, seeded with what was counted. */
        private void estimate() {
            this.distinct = new HyperLogLog();
            this.heavy = new HashMap<>();
            List<Map.Entry<String, long[]>> ranked = new ArrayList<>(this.counts.entrySet());
            ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (Map.Entry<String, long[]> entry : ranked) {
                this.distinct.add(entry.getKey());
            }
            for (int i = 0; i < Math.min(HEAVY_HITTERS, ranked.size()); i++) {
                this.heavy.put(ranked.get(i).getKey(), new long[] {ranked.get(i).getValue()[0]});
            }
            long cost = 0;
            for (String key : this.counts.keySet()) {
                cost += ENTRY_BYTES + 2L * key.length();
            }
            ReportStatistics.this.memory.release(cost);
            ReportStatistics.this.held -= cost;
            this.counts = null;
        }

        /** Misra-Gries, decremented in batches: a value seen more than rows / HEAVY_HITTERS times is always kept. */
        private void hit(String text) {
            long[] seen = this.heavy.get(text);
            if (seen != null) {
                seen[0]++;
                return;
            }
            this.heavy.put(text, new long[] {1});
            if (this.heavy.size() > 2 * HEAVY_HITTERS) {
                long[] values = new long[this.heavy.size()];
                int i = 0;
                for (long[] count : this.heavy.values()) {
                    values[i++] = count[0];
                }
                Arrays.sort(values);
                long cut = values[values.length - HEAVY_HITTERS - 1];
                this.heavyDecrements += cut;
                this.heavy.values().forEach(count -> count[0] -= cut);
                this.heavy.values().removeIf(count -> count[0] <= 0);
            }
        }

        boolean exact() {
            return this.counts != null;
        }

        long distinctCount() {
            return this.counts != null ? this.counts.size() : this.distinct.estimate();
        }

        List<Object> row() {
            List<Object> row = new ArrayList<>(HEADER.size());
            long values = this.rows - this.empty;
            boolean asNumbers = this.numeric && values > 0;
            row.add(this.name);
            row.add(this.rows);
            row.add(this.empty);
            row.add(this.exact() ? (Object) this.distinctCount() : "~" + String.format("%,d", this.distinctCount()) + " (estimate)");
            row.add(values == 0 ? null : asNumbers ? (Object) this.min : this.minText);
            row.add(values == 0 ? null : asNumbers ? (Object) this.max : this.maxText);
            row.add(asNumbers ? this.sum : null);
            row.add(asNumbers ? mean(this.sum, values) : null);
            row.add(ReportStatistics.this.top == 0 ? null : this.mostFrequent());
            return row;
        }

        String mostFrequent() {
            Map<String, long[]> source = this.exact() ? this.counts : this.heavy;
            if (source == null || source.isEmpty()) {
                return null;
            }
            List<Map.Entry<String, long[]>> ranked = new ArrayList<>(source.entrySet());
            ranked.sort((a, b) -> {
                int byCount = Long.compare(b.getValue()[0], a.getValue()[0]);
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            });
            if (this.exact() && ranked.get(0).getValue()[0] == 1 && ranked.size() > 1) {
                return "every value once";
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(ReportStatistics.this.top, ranked.size()); i++) {
                String value = ranked.get(i).getKey();
                if (value.length() > 40) {
                    value = value.substring(0, 37) + "...";
                }
                out.append(i == 0 ? "" : "; ").append(value).append(" (")
                    .append(this.exact() ? "" : "at least ").append(String.format("%,d", ranked.get(i).getValue()[0])).append(')');
            }
            return out.toString();
        }
    }

    static BigDecimal mean(BigDecimal sum, long count) {
        if (count == 0) {
            return null;
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP).stripTrailingZeros();
        return mean.scale() < 0 ? mean.setScale(0) : mean;
    }

    /** A value as a number when it is one: a number, or text that reads as one ("12", "-3.5", "1e3"); else null. */
    static BigDecimal number(Object value, String text) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : BigDecimal.valueOf(d);
        }
        if (value instanceof Boolean) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > 40 || !NUMBER.matcher(trimmed).matches()) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Equal-width bins between a numeric column's min and max (its statistics), counted in a second pass. */
    static final class Histogram {
        static final int BINS = 10;
        static final int BAR = 40;
        static final List<String> HEADER = Arrays.asList("from", "to", "rows", "share", "distribution");

        final String column;
        private final BigDecimal min;
        private final BigDecimal width;
        private final long[] counts = new long[BINS];
        private long total;

        Histogram(String column, BigDecimal min, BigDecimal max) {
            this.column = column;
            this.min = min;
            BigDecimal span = max.subtract(min);
            this.width = span.signum() == 0 ? BigDecimal.ONE : span.divide(BigDecimal.valueOf(BINS), 10, RoundingMode.HALF_UP);
        }

        void add(Object value) {
            if (value == null) {
                return;
            }
            BigDecimal number = number(value, value.toString());
            if (number == null) {
                return;
            }
            int bin = number.subtract(this.min).divide(this.width, 0, RoundingMode.FLOOR).intValue();
            this.counts[Math.max(0, Math.min(BINS - 1, bin))]++;
            this.total++;
        }

        List<List<Object>> rows() {
            long most = 1;
            for (long count : this.counts) {
                most = Math.max(most, count);
            }
            List<List<Object>> out = new ArrayList<>();
            for (int b = 0; b < BINS; b++) {
                BigDecimal from = this.min.add(this.width.multiply(BigDecimal.valueOf(b))).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
                BigDecimal to = this.min.add(this.width.multiply(BigDecimal.valueOf(b + 1L))).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
                long count = this.counts[b];
                String share = this.total == 0 ? "0%" : BigDecimal.valueOf(count * 100.0 / this.total).setScale(1, RoundingMode.HALF_UP) + "%";
                StringBuilder bar = new StringBuilder();
                for (int i = 0; i < Math.round(count * (double) BAR / most); i++) {
                    bar.append('#');
                }
                out.add(Arrays.<Object>asList(from.toPlainString(), (b == BINS - 1 ? "" : "< ") + to.toPlainString(), count, share,
                    bar.toString()));
            }
            return out;
        }

        long[] counts() {
            return this.counts.clone();
        }
    }

    /** HyperLogLog with 2^14 registers (16 KB a column): about 0.8% standard error. */
    static final class HyperLogLog {
        private static final int P = 14;
        private static final int M = 1 << P;
        private final byte[] registers = new byte[M];

        void add(String value) {
            long hash = hash(value);
            int index = (int) (hash >>> (64 - P));
            int rank = Long.numberOfLeadingZeros((hash << P) | (1L << (P - 1))) + 1;
            if (rank > this.registers[index]) {
                this.registers[index] = (byte) rank;
            }
        }

        long estimate() {
            double sum = 0;
            int zeros = 0;
            for (byte register : this.registers) {
                sum += 1.0 / (1L << register);
                if (register == 0) {
                    zeros++;
                }
            }
            double alpha = 0.7213 / (1 + 1.079 / M);
            double raw = alpha * M * M / sum;
            if (raw <= 2.5 * M && zeros > 0) {
                raw = M * Math.log((double) M / zeros);
            }
            return Math.round(raw);
        }

        /** FNV-1a over the UTF-8 bytes, then MurmurHash3's finaliser, so every bit depends on every byte. */
        static long hash(String value) {
            long h = 0xcbf29ce484222325L;
            for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
                h ^= b & 0xff;
                h *= 0x100000001b3L;
            }
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= h >>> 33;
            return h;
        }
    }
}
