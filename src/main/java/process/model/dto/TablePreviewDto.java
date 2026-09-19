package process.model.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * A window onto tabular data -- a CSV, a sheet, a parquet file, a JSON array -- as the object
 * browser shows it: column names, a page of rows as strings, and how many rows there are.
 */
public class TablePreviewDto {

    /** csv | tsv | xlsx | parquet | jsonl | json */
    private String source;
    private List<String> columns = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
    private int offset;
    private int limit;
    /** -1 when unknown (a CSV longer than the count cap). */
    private long totalRows;
    /** Names of the sheets, for a workbook; empty otherwise. */
    private List<String> sheets = new ArrayList<>();
    /** The sheet these rows came from, for a workbook. */
    private String sheet;
    private String note;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<List<String>> getRows() { return rows; }
    public void setRows(List<List<String>> rows) { this.rows = rows; }
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long totalRows) { this.totalRows = totalRows; }
    public List<String> getSheets() { return sheets; }
    public void setSheets(List<String> sheets) { this.sheets = sheets; }
    public String getSheet() { return sheet; }
    public void setSheet(String sheet) { this.sheet = sheet; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
