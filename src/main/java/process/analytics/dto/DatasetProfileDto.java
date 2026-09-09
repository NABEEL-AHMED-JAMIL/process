package process.analytics.dto;

import java.util.List;

/**
 * Everything the Profile tab and the Quality tab draw, from one scan.
 *
 * The two tabs are one payload rather than two endpoints because a file open already costs three
 * sessions and three governor permits against a ceiling of four (gap 17), and a Quality endpoint
 * beside a Profile endpoint would have made that five for the same numbers twice. Quality is not a
 * second measurement of this dataset; it is this measurement, read for a different question. That is
 * why the flags sit on ColumnProfileDto next to the statistics they were derived from -- a reader
 * who wants to know why a column was called a key can see the estimate that said so.
 *
 * totalRows arrives free. SUMMARIZE reports the relation's row count on every row it returns, so
 * this endpoint answers the count question without the count(*) that preview() pays for -- and,
 * unlike almost everything else here, that number is exact.
 *
 * <b>Read ColumnProfileDto's javadoc before rendering any of this.</b> Three of its figures are
 * estimates, two of its flags rest on an estimate, and which is which is not visible from the
 * field types.
 *
 * <b>Not answered here, and not to be improvised.</b> Duplicate rows. It is the one thing gap 25
 * names that SUMMARIZE genuinely cannot reach -- count(*) - count(DISTINCT (all columns)) is its
 * own scan and its own session, and on a wide dataset it is the most expensive query this module
 * would issue. Whether that cost is worth a duplicate count is a decision for whoever wants the
 * number, not something to approximate from what is here.
 *
 * @author Nabeel Ahmed
 */
public class DatasetProfileDto {

    private String bucket;
    private String path;
    private String format;
    /** True when the path is a pattern, so every statistic below spans all matching files. */
    private boolean multiFile;
    /** Exact, and free: SUMMARIZE carries it on every row, so no count(*) was run for it. */
    private long totalRows;
    private List<ColumnProfileDto> columns;

    public DatasetProfileDto() {}

    public DatasetProfileDto(String bucket, String path, String format,
        boolean multiFile, long totalRows, List<ColumnProfileDto> columns) {
        this.bucket = bucket;
        this.path = path;
        this.format = format;
        this.multiFile = multiFile;
        this.totalRows = totalRows;
        this.columns = columns;
    }

    public String getBucket() { return this.bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getPath() { return this.path; }
    public void setPath(String path) { this.path = path; }

    public String getFormat() { return this.format; }
    public void setFormat(String format) { this.format = format; }

    public boolean isMultiFile() { return this.multiFile; }
    public void setMultiFile(boolean multiFile) { this.multiFile = multiFile; }

    public long getTotalRows() { return this.totalRows; }
    public void setTotalRows(long totalRows) { this.totalRows = totalRows; }

    public List<ColumnProfileDto> getColumns() { return this.columns; }
    public void setColumns(List<ColumnProfileDto> columns) { this.columns = columns; }
}
