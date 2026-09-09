package process.analytics.dto;

import java.util.List;

/**
 * One page of a dataset.
 *
 * Rows are lists of strings rather than a list of maps, which halves the payload on a wide file
 * by not repeating every column name on every row, and keeps the column ORDER the file had --
 * a map would be at the mercy of JSON key ordering, and column order is information in a CSV.
 *
 * Values are already rendered as text. A DuckDB DECIMAL or TIMESTAMP has no JSON equivalent that
 * survives unchanged, and a big integer that arrives as a JavaScript double has silently lost
 * precision before anyone looks at it.
 *
 * @author Nabeel Ahmed
 */
public class DatasetPreviewDto {

    private List<String> columns;
    private List<List<String>> rows;
    private int page;
    private int pageSize;
    /** Rows in the whole dataset, across every file when the path is a pattern. */
    private long totalRows;
    private boolean multiFile;

    public DatasetPreviewDto() {}

    public DatasetPreviewDto(List<String> columns, List<List<String>> rows,
        int page, int pageSize, long totalRows, boolean multiFile) {
        this.columns = columns;
        this.rows = rows;
        this.page = page;
        this.pageSize = pageSize;
        this.totalRows = totalRows;
        this.multiFile = multiFile;
    }

    public List<String> getColumns() { return this.columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<List<String>> getRows() { return this.rows; }
    public void setRows(List<List<String>> rows) { this.rows = rows; }

    public int getPage() { return this.page; }
    public void setPage(int page) { this.page = page; }

    public int getPageSize() { return this.pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }

    public long getTotalRows() { return this.totalRows; }
    public void setTotalRows(long totalRows) { this.totalRows = totalRows; }

    public boolean isMultiFile() { return this.multiFile; }
    public void setMultiFile(boolean multiFile) { this.multiFile = multiFile; }
}
