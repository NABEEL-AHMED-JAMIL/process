package process.util;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Owner 2026-10-27 ("add a sort on each table"): a paged list's column sort. Each endpoint keeps a FIXED allow-list of the
 * columns it sorts by -- an API name mapped, in code, to the SQL (or Java key) it sorts on -- so a request only ever picks a
 * key of that map and its text never reaches a statement. An unknown column, or a direction other than asc/desc, is a 400
 * naming what is allowed; no sort is the list's own order, exactly as before.
 *
 * Every sort is total: empty values last in BOTH directions ("no value" is missing, not small or large), and ties broken by
 * the row's id in the sort's direction, so a page never repeats or skips a row.
 */
public final class ListSort {

    private ListSort() {
    }

    public enum Dir {
        ASC, DESC
    }

    /**
     * The request named a column or direction the list does not sort by: HTTP 400 with this sentence (GlobalExceptionHandler).
     * Not a {@link RequestRefused}: that one is a 200 + ERROR (ADR-023), and the owner asked for a 400 here.
     */
    public static final class Refused extends IllegalArgumentException {

        public Refused(String sentence) {
            super(sentence);
        }
    }

    /** The column and direction a request asked for. */
    public static final class Choice {
        public final String column;
        public final Dir dir;

        public Choice(String column, Dir dir) {
            this.column = column;
            this.dir = dir;
        }

        public boolean ascending() {
            return this.dir == Dir.ASC;
        }

        /** "desc" or "asc", as the request spells it (for an answer that says which order it is in). */
        public String dirWord() {
            return this.dir.name().toLowerCase(Locale.ROOT);
        }
    }

    /** One sortable SQL column: its expression, written in code, and whether it can be empty. */
    public static final class Column {
        public final String sql;
        public final boolean nullable;

        private Column(String sql, boolean nullable) {
            this.sql = sql;
            this.nullable = nullable;
        }

        /** A column that is never empty: plain ASC/DESC, so an index on it serves both directions. */
        public static Column of(String sql) {
            return new Column(sql, false);
        }

        /** A column that can be empty: NULLS LAST whichever the direction. */
        public static Column nullable(String sql) {
            return new Column(sql, true);
        }
    }

    /**
     * The sort a request asks for, or null for the list's own order. {@code allowed} in the order the refusal names them.
     *
     * @throws Refused an unknown column ("sort must be one of: ...") or direction ("dir must be asc or desc.")
     */
    public static Choice parse(String sort, String dir, Collection<String> allowed) {
        String direction = dir == null ? "" : dir.trim().toLowerCase(Locale.ROOT);
        if (!direction.isEmpty() && !"asc".equals(direction) && !"desc".equals(direction)) {
            throw new Refused("dir must be asc or desc.");
        }
        if (sort == null || sort.trim().isEmpty()) {
            return null;
        }
        String column = sort.trim();
        if (!allowed.contains(column)) {
            throw new Refused("sort must be one of: " + String.join(", ", allowed) + ".");
        }
        return new Choice(column, "desc".equals(direction) ? Dir.DESC : Dir.ASC);
    }

    /**
     * "expr DESC NULLS LAST, id DESC": the ORDER BY for a choice, built only from {@code columns}' SQL and {@code idSql} (both
     * written in code). The id column itself needs no tiebreak.
     */
    public static String orderBy(Choice choice, Map<String, Column> columns, String idSql) {
        Column column = columns.get(choice.column);
        if (column == null) {
            throw new IllegalStateException("No SQL for the sort column " + choice.column);
        }
        String dir = choice.dir.name();
        String first = column.sql + " " + dir + (column.nullable ? " NULLS LAST" : "");
        return column.sql.equals(idSql) ? first : first + ", " + idSql + " " + dir;
    }

    /** For a list sorted in Java: by the key (empty last both ways), then the tiebreak in the same direction. */
    public static <T, K extends Comparable<? super K>> Comparator<T> comparator(Function<T, K> key, Dir dir, Comparator<T> tiebreak) {
        Comparator<K> natural = dir == Dir.ASC ? Comparator.<K>naturalOrder() : Comparator.<K>reverseOrder();
        Comparator<T> byKey = Comparator.comparing(key, Comparator.nullsLast(natural));
        return byKey.thenComparing(dir == Dir.ASC ? tiebreak : tiebreak.reversed());
    }
}
