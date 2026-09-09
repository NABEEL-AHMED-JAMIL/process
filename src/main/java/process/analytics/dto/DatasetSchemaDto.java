package process.analytics.dto;

import java.util.List;

/**
 * What a dataset looks like, without any of its rows.
 *
 * @author Nabeel Ahmed
 */
public class DatasetSchemaDto {

    private String bucket;
    private String path;
    private String format;
    /** True when the path is a pattern, so the schema is the union across every matching file. */
    private boolean multiFile;
    private List<ColumnDto> columns;

    public DatasetSchemaDto() {}

    public DatasetSchemaDto(String bucket, String path, String format,
        boolean multiFile, List<ColumnDto> columns) {
        this.bucket = bucket;
        this.path = path;
        this.format = format;
        this.multiFile = multiFile;
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

    public List<ColumnDto> getColumns() { return this.columns; }
    public void setColumns(List<ColumnDto> columns) { this.columns = columns; }
}
