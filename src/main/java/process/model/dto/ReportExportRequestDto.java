package process.model.dto;

import java.util.List;

/**
 * A report grid the caller wants turned into a file.
 *
 * The grid is sent rather than re-queried because the user is exporting exactly what they are
 * looking at -- the dimensions, measure and filters they arrived at by clicking. Re-deriving it
 * from a query would risk exporting something subtly different from what is on screen.
 */
public class ReportExportRequestDto {

    /** Used for the sheet title and the generated filename. */
    private String title;
    private List<String> columns;
    private List<List<Object>> rows;
    /** csv or xlsx. */
    private String format;
    /** download, bucket or submit. */
    private String destination;
    private String bucket;
    private String folder;
    /** Where to POST the file when destination is submit. */
    private String submitUrl;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<List<Object>> getRows() { return rows; }
    public void setRows(List<List<Object>> rows) { this.rows = rows; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    public String getSubmitUrl() { return submitUrl; }
    public void setSubmitUrl(String submitUrl) { this.submitUrl = submitUrl; }
}
