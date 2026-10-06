package process.pipeline.data;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A Parquet file's rows, read with DuckDB (MIG-231) -- as media-service reads and writes Parquet, with no Hadoop or
 * Parquet jars. An in-memory DuckDB per read, one thread and 256 MB, on a temporary copy of the file that is deleted
 * afterwards. Values come back as a row holds them: numbers, text, true/false; dates, times, decimals beyond a double
 * and nested values as their text.
 */
public final class ParquetRows {

    private ParquetRows() {
    }

    public static void read(byte[] content, RowCollector rows) throws Exception {
        Path file = Files.createTempFile("pipeline-read-", ".parquet");
        try {
            Files.write(file, content);
            Class.forName("org.duckdb.DuckDBDriver");
            Properties settings = new Properties();
            settings.setProperty("threads", "1");
            settings.setProperty("memory_limit", "256MB");
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", settings);
                 Statement statement = duck.createStatement();
                 // The path is a temporary file this method named: no request text reaches the query.
                 ResultSet result = statement.executeQuery("SELECT * FROM read_parquet('" + file.toAbsolutePath().toString().replace("'", "''")
                     + "') LIMIT " + (Limits.MAX_ROWS + 1))) {
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
