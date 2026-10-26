package process.pipeline.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;

/**
 * A Parquet file's rows, read with DuckDB (MIG-231; streamed since ZT-S3, {@link #stream}) -- as media-service reads and writes Parquet, with no Hadoop or
 * Parquet jars. An in-memory DuckDB per read, one thread and 256 MB, on a temporary copy of the file that is deleted
 * afterwards. Values come back as a row holds them: numbers, text, true/false; dates, times, decimals beyond a double
 * and nested values as their text.
 */
public final class ParquetRows {

    private ParquetRows() {
    }

    public static void read(byte[] content, RowCollector rows) throws Exception {
        read(content, rows, null);
    }

    /**
     * MIG-365: only the named columns (null: all), read by DuckDB alone -- a Parquet file is columnar, so the others are
     * never decoded. A column the file does not have fails the read and lists the ones it has.
     */
    public static void read(byte[] content, RowCollector rows, List<String> only) throws Exception {
        Path file = Files.createTempFile("pipeline-read-", ".parquet");
        try {
            Files.write(file, content);
            Class.forName("org.duckdb.DuckDBDriver");
            Properties settings = new Properties();
            settings.setProperty("threads", "1");
            settings.setProperty("memory_limit", "256MB");
            String source = "read_parquet('" + file.toAbsolutePath().toString().replace("'", "''") + "')";
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", settings);
                 Statement statement = duck.createStatement();
                 // The path is a temporary file this method named, and the columns are checked against the file's own and
                 // quoted as identifiers: no request text reaches the query unquoted.
                 ResultSet result = statement.executeQuery("SELECT " + projection(statement, source, only) + " FROM " + source
                     + " LIMIT " + (Limits.MAX_ROWS + 1))) {
                ResultSetMetaData meta = result.getMetaData();
                int columns = meta.getColumnCount();
                while (result.next() && !rows.full()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("The Parquet read was stopped.");
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(meta.getColumnLabel(i), plain(result.getObject(i)));
                    }
                    rows.add(row);
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** What a streamed read does with each row: false stops the read (enough rows). */
    @FunctionalInterface
    public interface RowHandler {
        boolean accept(Map<String, Object> row) throws Exception;
    }

    /**
     * ZT-S3: a Parquet file's rows as a stream -- the bytes go to a temporary file (DuckDB reads a file, not a stream), then
     * DuckDB hands the rows over a chunk at a time (jdbc_stream_results), a row group read at a time within its 256 MB, so
     * no table is held in memory and the step's row bound, not the in-memory one, is the limit. The named columns only (null:
     * all), as {@link #read}. The handler answering false ends the read early.
     */
    public static void stream(InputStream content, List<String> only, RowHandler each) throws Exception {
        Path file = Files.createTempFile("pipeline-stream-", ".parquet");
        try {
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            Class.forName("org.duckdb.DuckDBDriver");
            Properties settings = new Properties();
            settings.setProperty("threads", "1");
            settings.setProperty("memory_limit", "256MB");
            settings.setProperty("jdbc_stream_results", "true");
            String source = "read_parquet('" + file.toAbsolutePath().toString().replace("'", "''") + "')";
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", settings);
                 Statement statement = duck.createStatement()) {
                // As in read: the path is this method's temporary file and the columns are checked and quoted.
                String projection = projection(statement, source, only);
                try (ResultSet result = statement.executeQuery("SELECT " + projection + " FROM " + source)) {
                    ResultSetMetaData meta = result.getMetaData();
                    int columns = meta.getColumnCount();
                    while (result.next()) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("The Parquet read was stopped.");
                        }
                        Map<String, Object> row = new LinkedHashMap<>(columns * 4 / 3 + 1);
                        for (int i = 1; i <= columns; i++) {
                            row.put(meta.getColumnLabel(i), plain(result.getObject(i)));
                        }
                        if (!each.accept(row)) {
                            return;
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** "*", or the named columns quoted, each checked to be the file's; the file's own names listed when one is not. */
    private static String projection(Statement statement, String source, List<String> columns) throws Exception {
        if (columns == null) {
            return "*";
        }
        List<String> has = new ArrayList<>();
        try (ResultSet described = statement.executeQuery("DESCRIBE SELECT * FROM " + source)) {
            while (described.next()) {
                has.add(described.getString(1));
            }
        }
        List<String> missing = new ArrayList<>();
        StringBuilder select = new StringBuilder();
        for (String column : columns) {
            if (!has.contains(column)) {
                missing.add(column);
            }
            select.append(select.length() == 0 ? "" : ", ").append('"').append(column.replace("\"", "\"\"")).append('"');
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(String.format("The file has no column %s; its columns are: %s.", String.join(", ", missing),
                String.join(", ", has)));
        }
        return select.toString();
    }

    static Object plain(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Integer
            || value instanceof Short || value instanceof Byte || value instanceof Double || value instanceof Float) {
            return value instanceof Integer || value instanceof Short || value instanceof Byte ? (Object) ((Number) value).longValue()
                : value instanceof Float ? (Object) ((Float) value).doubleValue() : value;
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            return Values.plain(new BigDecimal(value.toString()));
        }
        return value.toString();
    }
}
