package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.sql.Timestamp;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryScheduleDto {

    private Long scheduleId;
    private Long queryId;
    private String queryName;
    private Long databaseConnectionProfileId;
    private String outputBucket;
    private String outputPrefix;
    private String outputFileNameTemplate;
    private Integer intervalMinutes;
    private Timestamp nextRunAt;
    private Status status;


    private Long createdBy;
    private String createdByName;
    private Long updatedBy;
    private String updatedByName;

    public QueryScheduleDto() {}

    public Long getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(Long scheduleId) {
        this.scheduleId = scheduleId;
    }

    public Long getQueryId() {
        return queryId;
    }

    public void setQueryId(Long queryId) {
        this.queryId = queryId;
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String queryName) {
        this.queryName = queryName;
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

    public String getOutputFileNameTemplate() {
        return outputFileNameTemplate;
    }

    public void setOutputFileNameTemplate(String outputFileNameTemplate) {
        this.outputFileNameTemplate = outputFileNameTemplate;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public Timestamp getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Timestamp nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}
