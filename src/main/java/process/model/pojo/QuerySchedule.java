package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A reusable recurring-execution configuration for one QueryDefinition -- stores only
 * references (queryId, databaseConnectionProfileId, output location) plus a schedule, never a
 * copy of the query itself, so ProcessCron.pollDueQuerySchedules() and a manual "Run" click
 * both end up calling the exact same QueryExecutionService.execute(...) path (§8 of the design:
 * one execution engine, two triggers).
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "query_schedule", indexes = {
    @Index(name = "idx_query_schedule_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_query_schedule_next_run_at", columnList = "next_run_at")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuerySchedule {

    @GenericGenerator(
        name = "queryScheduleSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "query_schedule_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "schedule_id")
    @GeneratedValue(generator = "queryScheduleSequenceGenerator")
    private Long scheduleId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "query_id", nullable = false)
    private Long queryId;

    @Column(name = "database_connection_profile_id", nullable = false)
    private Long databaseConnectionProfileId;

    @Column(name = "output_bucket", nullable = false)
    private String outputBucket;

    /** Key prefix, e.g. "reports/daily/" -- see ObjectStorageService.createFolder's own
     * "key already ending in /" convention, reused as-is here. */
    @Column(name = "output_prefix", nullable = false)
    private String outputPrefix;

    /** May contain simple date tokens (e.g. "{date}") resolved at execution time -- see
     * QueryExecutionServiceImpl.resolveOutputFileName. */
    @Column(name = "output_file_name_template", nullable = false)
    private String outputFileNameTemplate;

    /** Plain interval in minutes -- matches ProcessCron's own fixedDelay-polling style rather
     * than introducing a cron-expression parser this app doesn't otherwise have. */
    @Column(name = "interval_minutes", nullable = false)
    private Integer intervalMinutes;

    @Column(name = "next_run_at", nullable = false)
    private Timestamp nextRunAt;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    public QuerySchedule() {}

    public Long getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(Long scheduleId) {
        this.scheduleId = scheduleId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
