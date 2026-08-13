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

@Component
public class CsvExportService {

    private static final int FETCH_SIZE = 500;

    private static final byte[] UTF8_BOM = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    public CsvExportResult streamToCsvFile(Connection connection, String sql, long maxRows, int queryTimeoutSeconds)
        throws SQLException, IOException {
        File tempFile = File.createTempFile("query-export-", ".csv");
        try {

            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                return this.doStream(connection, sql, maxRows, queryTimeoutSeconds, tempFile);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException | IOException | RuntimeException ex) {

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
