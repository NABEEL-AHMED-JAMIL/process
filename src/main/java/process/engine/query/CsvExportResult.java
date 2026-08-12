package process.engine.query;

import java.io.File;

/**
 * Result of CsvExportService.streamToCsvFile -- file is a local temp file the caller must
 * delete once it's been uploaded (see QueryExecutionServiceImpl's finally block).
 * @author Nabeel Ahmed
 */
public final class CsvExportResult {

    private final File file;
    private final long rowCount;
    private final boolean truncated;

    public CsvExportResult(File file, long rowCount, boolean truncated) {
        this.file = file;
        this.rowCount = rowCount;
        this.truncated = truncated;
    }

    public File getFile() {
        return this.file;
    }

    public long getRowCount() {
        return this.rowCount;
    }

    public boolean isTruncated() {
        return this.truncated;
    }

}
