package process.engine.query;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams a JDBC ResultSet straight to a CSV file, row by row, via Apache Commons CSV
 * (CSVPrinter already handles RFC 4180 escaping correctly -- commas, quotes, embedded newlines,
 * null values as empty fields -- so none of that is hand-rolled here). Never builds a
 * List&lt;Map&lt;String,Object&gt;&gt; of the result: at most one row is held in memory at a
 * time, and the JDBC fetchSize below caps how many rows the driver buffers client-side per
 * network round trip. The destination is a local temp file rather than a true zero-buffer pipe
 * into object storage -- see DatabaseConnectionFactory/QueryExecutionServiceImpl's javadocs for
 * why: ObjectStorageService.uploadObject needs a known size upfront, and a bounded local temp
 * file (deleted immediately after upload) is a reasonable, much smaller-footprint middle ground
 * than an in-memory list for what "streaming" is actually protecting against here.
 * @author Nabeel Ahmed
 */
@Component
public class CsvExportService {

    /** Rows fetched from the driver per network round trip -- keeps the driver's own client-side
     * buffer small regardless of total result size, instead of the JDBC default of fetching the
     * entire result set at once (Postgres: unless autocommit is off + this is set, the driver
     * ignores fetchSize entirely and pulls everything -- see the transaction handling below). */
    private static final int FETCH_SIZE = 500;

    private static final byte[] UTF8_BOM = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    /**
     * Method use to run sql against connection and stream every row straight to a new local
     * temp CSV file, capped at maxRows and queryTimeoutSeconds.
     * @param connection an already-open connection this method does NOT close (caller owns it)
     * @param sql already-validated (see QueryValidator), read-only, single-statement SQL
     * @param maxRows hard row cap -- streaming stops (truncated=true) once reached, doesn't fail
     * @param queryTimeoutSeconds JDBC Statement-level timeout
     * @return CsvExportResult
     * */
    public CsvExportResult streamToCsvFile(Connection connection, String sql, long maxRows, int queryTimeoutSeconds)
        throws SQLException, IOException {
        File tempFile = File.createTempFile("query-export-", ".csv");
        try {
            // autoCommit must be off for Postgres' JDBC driver to actually honor fetchSize and
            // stream server-side, rather than materializing the whole result set into the
            // driver's client-side buffer on the first next() call regardless of what fetchSize
            // says -- restored in the finally block since this Connection may be reused by the
            // caller (it isn't here, but the factory contract doesn't promise single-use).
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                return this.doStream(connection, sql, maxRows, queryTimeoutSeconds, tempFile);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException | IOException | RuntimeException ex) {
            // Don't leave a partial/broken file behind on failure -- the caller has nothing
            // useful to upload if this method didn't return successfully.
            tempFile.delete();
            throw ex;
        }
    }

    private CsvExportResult doStream(Connection connection, String sql, long maxRows,
        int queryTimeoutSeconds, File tempFile) throws SQLException, IOException {
        long rowCount = 0;
        boolean truncated = false;
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<String> headers = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    headers.add(metaData.getColumnLabel(i));
                }
                try (FileOutputStream fileOut = new FileOutputStream(tempFile)) {
                    fileOut.write(UTF8_BOM);
                    try (Writer writer = new OutputStreamWriter(fileOut, StandardCharsets.UTF_8);
                        CSVPrinter printer = new CSVPrinter(writer,
                            CSVFormat.DEFAULT.withHeader(headers.toArray(new String[0])))) {
                        Object[] rowBuffer = new Object[columnCount];
                        while (resultSet.next()) {
                            if (rowCount >= maxRows) {
                                truncated = true;
                                break;
                            }
                            for (int i = 1; i <= columnCount; i++) {
                                // getObject(i) is null for SQL NULL -- CSVPrinter writes that as
                                // an empty field, which is exactly the "null values" handling
                                // the design calls for (distinguishable from an empty string
                                // only by the surrounding quotes CSVPrinter adds when needed,
                                // same tradeoff every plain-CSV export makes).
                                rowBuffer[i - 1] = resultSet.getObject(i);
                            }
                            printer.printRecord(rowBuffer);
                            rowCount++;
                        }
                        printer.flush();
                    }
                }
            }
        }
        return new CsvExportResult(tempFile, rowCount, truncated);
    }

}
