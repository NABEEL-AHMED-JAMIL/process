package process.analytics.dto;

import java.util.List;

/**
 * The answer to a query a person wrote.
 *
 * The same shape as a dataset page and for the same reasons -- rows as lists of strings, so a wide
 * result does not repeat every column name on every row and the column ORDER the query asked for
 * survives; values already rendered as text, because a DECIMAL that arrives in a browser as a
 * JavaScript double has lost precision before anyone looks at it.
 *
 * What is different is truncated, and it is the field this type exists for. A query with no LIMIT
 * of its own is wrapped in analytics.query.max-rows before it runs, so a result can stop at the
 * ceiling rather than at the end of the data. A user handed ten thousand rows out of forty
 * thousand, and not told, has not been given a partial answer -- they have been given a wrong one,
 * and they will go and act on it.
 *
 * <b>queryId, status and durationMs are what 09's response principles ask for</b> -- a stable id,
 * a state and a timing -- and the id is the load-bearing one: without it a caller cannot name the
 * run it is looking at, which is why there was no way to cancel one. What it does NOT identify is
 * the history row; that has its own sequence id and no column to hold this one, so the two are
 * correlated by connection, path and time and not by key. Saying so here is cheaper than somebody
 * discovering it while writing a join.
 *
 * @author Nabeel Ahmed
 */
public class QueryResultDto {

    private List<String> columns;

    /**
     * The same columns, with the type the engine gave each one.
     *
     * <b>This is 09's "typed column metadata", and it is a correctness field rather than a
     * decorative one.</b> Every value below is a String, which is deliberate -- a DECIMAL that
     * arrives in a browser as a JavaScript double has already lost precision -- but a string with
     * no type beside it cannot be rendered. A client cannot tell "0123" the postcode from 123 the
     * number, cannot right-align the numbers, and cannot format a date, so it either guesses from
     * the characters or renders everything as text. Both are wrong on somebody's file.
     *
     * <b>Beside {@link #columns} rather than replacing it.</b> The names are what every existing
     * caller reads and the Angular client's table is built on a string array; widening that field
     * would be a breaking change to a payload two other agents are working in, to add information
     * that fits perfectly well next to it. The two lists are the same columns in the same order.
     */
    private List<ColumnDto> columnMeta;

    private List<List<String>> rows;
    /**
     * The id this run answered to, which is the id a cancel request names.
     *
     * Either the id the caller sent or one minted for it. A caller that did not send one gets this
     * back after the query is over, which is honest but not much use: /query is synchronous, so a
     * client that means to offer a stop button has to name the run on the way in.
     */
    private String queryId;
    /** The lifecycle state this run ended in. COMPLETED whenever a result exists to carry it. */
    private String status;
    /** Wall clock across the governed path -- the wait for a permit included, not just the scan. */
    private Long durationMs;
    /** Rows in THIS result, which is the whole answer only when truncated is false. */
    private int rowCount;
    /**
     * Whether the row ceiling is what ended the result.
     *
     * True when the result came back full to the ceiling. A query whose answer is exactly
     * max-rows rows reports true as well, and that is the honest reading rather than a rounding
     * error: from outside the LIMIT the two are the same event, and the only way to tell them
     * apart is to run the query again without the ceiling -- which is the thing the ceiling
     * exists to prevent. "There may be more" is what this flag can claim, and a screen should say
     * it in those words.
     */
    private boolean truncated;

    public QueryResultDto() {}

    public QueryResultDto(List<String> columns, List<List<String>> rows, int rowCount,
        boolean truncated) {
        this.columns = columns;
        this.rows = rows;
        this.rowCount = rowCount;
        this.truncated = truncated;
    }

    public List<String> getColumns() { return this.columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<ColumnDto> getColumnMeta() { return this.columnMeta; }
    public void setColumnMeta(List<ColumnDto> columnMeta) { this.columnMeta = columnMeta; }

    public List<List<String>> getRows() { return this.rows; }
    public void setRows(List<List<String>> rows) { this.rows = rows; }

    public int getRowCount() { return this.rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public boolean isTruncated() { return this.truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }

    public String getQueryId() { return this.queryId; }
    public void setQueryId(String queryId) { this.queryId = queryId; }

    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDurationMs() { return this.durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
