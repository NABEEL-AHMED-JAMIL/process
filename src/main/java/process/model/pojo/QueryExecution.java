package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.QueryExecutionStatus;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * One record per query run (manual "Run" click or a QuerySchedule firing) -- the audit trail
 * and execution-history data source (§15 of the design). errorMessage is always a sanitized,
 * user-facing message (see QueryExecutionServiceImpl) -- never a raw JDBC/driver exception,
 * which can leak host/port/schema details.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "query_execution", indexes = {
    @Index(name = "idx_query_execution_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_query_execution_query_id", columnList = "query_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryExecution {

    @GenericGenerator(
        name = "queryExecutionSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "query_execution_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "execution_id")
    @GeneratedValue(generator = "queryExecutionSequenceGenerator")
    private Long executionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "query_id", nullable = false)
    private Long queryId;

    @Column(name = "database_connection_profile_id", nullable = false)
    private Long databaseConnectionProfileId;

    /** Null until a QuerySchedule triggers this row -- distinguishes a manual run from a
     * scheduled one in the execution-history UI. */
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private QueryExecutionStatus status;

    @Column(name = "started_at")
    private Timestamp startedAt;

    @Column(name = "completed_at")
    private Timestamp completedAt;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "output_bucket")
    private String outputBucket;

    @Column(name = "output_key")
    private String outputKey;

    /** Always a sanitized, user-facing message -- see this class's javadoc. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private Timestamp createdAt;

    public QueryExecution() {}

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
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

    public Long getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(Long scheduleId) {
        this.scheduleId = scheduleId;
    }

    public QueryExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(QueryExecutionStatus status) {
        this.status = status;
    }

    public Timestamp getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Timestamp startedAt) {
        this.startedAt = startedAt;
    }

    public Timestamp getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Timestamp completedAt) {
        this.completedAt = completedAt;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    public String getOutputBucket() {
        return outputBucket;
    }

    public void setOutputBucket(String outputBucket) {
        this.outputBucket = outputBucket;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
