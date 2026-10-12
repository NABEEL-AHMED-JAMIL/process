package process.pipeline.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The order the Sort and Limit steps put rows in (MIG-365): by one or more columns, each ascending or descending, with
 * empty values (null or blank text) first or last whatever the direction.
 *
 * A total order, so any sort of the same rows gives the same answer: a value that is a number, or text that reads as
 * one ("12", "-0.5", as a CSV holds it), sorts as a number; numbers come before text; text sorts by its characters (so
 * ISO dates sort in date order); true and false sort as the text "true" and "false". Two rows equal on every column
 * keep their input order (a stable sort).
 */
public final class RowOrder {

    /** One column of the order. */
    public static final class By {
        public final String column;
        public final boolean descending;
        public final boolean emptyFirst;

        public By(String column, boolean descending, boolean emptyFirst) {
            this.column = column;
            this.descending = descending;
            this.emptyFirst = emptyFirst;
        }
    }

    private final List<By> by;

    public RowOrder(List<By> by) {
        if (by == null || by.isEmpty()) {
            throw new IllegalArgumentException("An order names at least one column.");
        }
        this.by = Collections.unmodifiableList(new ArrayList<>(by));
    }

    public List<By> by() {
        return this.by;
    }

    /** The columns the order reads. */
    public List<String> columns() {
        List<String> columns = new ArrayList<>(this.by.size());
        this.by.forEach(b -> columns.add(b.column));
        return columns;
    }

    /** A row's sort key: one {@link Key} per column of the order, worked out once per row. */
    public Key[] keyOf(Map<String, Object> row) {
        Key[] keys = new Key[this.by.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = Key.of(row.get(this.by.get(i).column));
        }
        return keys;
    }

    /** Negative, zero or positive as {@code a}'s row sorts before, with or after {@code b}'s. */
    public int compare(Key[] a, Key[] b) {
        for (int i = 0; i < a.length; i++) {
            By column = this.by.get(i);
            Key x = a[i];
            Key y = b[i];
            if (x == Key.EMPTY || y == Key.EMPTY) {
                if (x == y) {
                    continue;
                }
                // Empty values go where they were asked to, in either direction.
                return (x == Key.EMPTY) == column.emptyFirst ? -1 : 1;
            }
            int order = x.compareTo(y);
            if (order != 0) {
                return column.descending ? -order : order;
            }
        }
        return 0;
    }

    /** One value as it sorts: empty, a number, or text. */
    public static final class Key implements Comparable<Key> {
        static final Key EMPTY = new Key(0, 0, 0L, false, null);

        private static final int NUMBER = 1;
        private static final int TEXT = 2;

        private final int kind;
        private final double number;
        private final long whole;
        private final boolean isWhole;
        private final String text;

        private Key(int kind, double number, long whole, boolean isWhole, String text) {
            this.kind = kind;
            this.number = number;
            this.whole = whole;
            this.isWhole = isWhole;
            this.text = text;
        }

        static Key of(Object value) {
            if (value == null) {
                return EMPTY;
            }
            if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
                long whole = ((Number) value).longValue();
                return new Key(NUMBER, whole, whole, true, null);
            }
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                return Double.isNaN(number) ? new Key(TEXT, 0, 0L, false, "NaN") : new Key(NUMBER, number, 0L, false, null);
            }
            String text = value instanceof Boolean ? value.toString() : value.toString();
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return EMPTY;
            }
            if (!(value instanceof Boolean) && Values.readsAsNumber(trimmed)) {
                try {
                    if (trimmed.length() < 19 && trimmed.indexOf('.') < 0 && trimmed.indexOf('e') < 0 && trimmed.indexOf('E') < 0) {
                        long whole = Long.parseLong(trimmed.charAt(0) == '+' ? trimmed.substring(1) : trimmed);
                        return new Key(NUMBER, whole, whole, true, null);
                    }
                    double number = Double.parseDouble(trimmed);
                    if (!Double.isInfinite(number)) {
                        return new Key(NUMBER, number, 0L, false, null);
                    }
                } catch (NumberFormatException notOne) {
                    // falls through to text
                }
            }
            return new Key(TEXT, 0, 0L, false, text);
        }

        @Override
        public int compareTo(Key other) {
            if (this.kind != other.kind) {
                return Integer.compare(this.kind, other.kind);
            }
            if (this.kind == NUMBER) {
                if (this.isWhole && other.isWhole) {
                    return Long.compare(this.whole, other.whole);
                }
                return Double.compare(this.number, other.number);
            }
            return this.text.compareTo(other.text);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key && this.compareTo((Key) other) == 0 && (this == EMPTY) == (other == EMPTY);
        }

        @Override
        public int hashCode() {
            return this.kind == TEXT ? this.text.hashCode() : Double.hashCode(this.isWhole ? (double) this.whole : this.number);
        }
    }
}
