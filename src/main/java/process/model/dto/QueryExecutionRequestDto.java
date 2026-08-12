package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.gson.Gson;

/**
 * The "execute" request body -- deliberately just IDs and output configuration, never a query
 * result or the query text itself. The backend loads the query and connection profile itself
 * (re-validating tenant ownership) rather than trusting anything about them from the frontend
 * beyond queryId/databaseConnectionProfileId -- see §5 of the design and
 * QueryExecutionServiceImpl.execute.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryExecutionRequestDto {

    private Long queryId;
    private Long databaseConnectionProfileId;
    private String outputBucket;
    private String outputPrefix;
    private String outputFileName;

    public QueryExecutionRequestDto() {}

    public Long getQueryId() {
        return queryId;
    }

    public void setQueryId(Long queryId) {
        this.queryId = queryId;
    }

    public Long getDatabaseConnectionProfileId() {
        return databaseConnectionProfileId;
    }

    public void setDatabaseConnectionProfileId(Long databaseConnectionProfileId) {
        this.databaseConnectionProfileId = databaseConnectionProfileId;
    }

    public String getOutputBucket() {
        return outputBucket;
    }

    public void setOutputBucket(String outputBucket) {
        this.outputBucket = outputBucket;
    }

    public String getOutputPrefix() {
        return outputPrefix;
    }

    public void setOutputPrefix(String outputPrefix) {
        this.outputPrefix = outputPrefix;
    }

    public String getOutputFileName() {
        return outputFileName;
    }

    public void setOutputFileName(String outputFileName) {
        this.outputFileName = outputFileName;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
