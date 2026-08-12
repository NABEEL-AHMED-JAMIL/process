package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Result of QueryDefinitionServiceImpl.previewQuery -- server-enforced-bounded (see
 * QueryValidator.PREVIEW_ROW_LIMIT), never the full result set. truncated is true when the
 * underlying query could have returned more rows than the preview cap allowed through.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryPreviewResponseDto {

    private List<String> columns;
    private List<Map<String, Object>> rows;
    private boolean truncated;

    public QueryPreviewResponseDto() {}

    public QueryPreviewResponseDto(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        this.columns = columns;
        this.rows = rows;
        this.truncated = truncated;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
