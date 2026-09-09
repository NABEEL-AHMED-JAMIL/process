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
    /**
     * Rows the pager has to page through: the whole dataset, or what a filter left of it.
     *
     * <b>Which of the two it is depends on {@link #filtered}, and reading one without the other is
     * the mistake this field is documented to prevent.</b> This number is what a page control
     * divides by the page size, so a filtered result reported with the file's own count offers
     * pages that return nothing -- and a person who filters 250,000 rows down to 1,204 and is shown
     * 2,500 pages has been told the filter did not work.
     */
    private long totalRows;
    private boolean multiFile;
    /**
     * Whether a filter or a search removed rows before they were counted.
     *
     * The screen needs it to phrase the count. "1,204 rows" is a claim about the dataset; "1,204 of
     * 250,000" is a claim about the filter, and only this flag tells them apart -- the number alone
     * cannot, because a small file and a narrow filter produce the same one.
     *
     * A sort does NOT set it. Ordering moves rows without removing any, and a grid that said
     * "filtered" because somebody clicked a column header would be crying wolf on every sort.
     */
    private boolean filtered;

    public DatasetPreviewDto() {}

    public DatasetPreviewDto(List<String> columns, List<List<String>> rows,
        int page, int pageSize, long totalRows, boolean multiFile) {
        this(columns, rows, page, pageSize, totalRows, multiFile, false);
    }

    public DatasetPreviewDto(List<String> columns, List<List<String>> rows,
        int page, int pageSize, long totalRows, boolean multiFile, boolean filtered) {
        this.columns = columns;
        this.rows = rows;
        this.page = page;
        this.pageSize = pageSize;
        this.totalRows = totalRows;
        this.multiFile = multiFile;
        this.filtered = filtered;
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

    public boolean isFiltered() { return this.filtered; }
    public void setFiltered(boolean filtered) { this.filtered = filtered; }
}
