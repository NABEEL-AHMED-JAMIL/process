package process.model.pojo;

import org.barco.platform.correlation.CorrelationId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.Filter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import process.model.enums.Status;

@Entity

@Table(name = "job_audit_logs", indexes = {
    @Index(name = "idx_job_audit_logs_job_queue_id", columnList = "job_queue_id"),
    @Index(name = "idx_job_audit_logs_correlation_id", columnList = "correlation_id")
})
/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class JobAuditLogs {

    @GenericGenerator(
        name = "jobAuditLogsSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "job_audit_logs_source_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "job_audit_log_id")
    @GeneratedValue(generator = "jobAuditLogsSequenceGenerator")
    private Long jobAuditLogId;

    @Column(name = "job_queue_id",
        nullable = false)
    private Long jobQueueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_queue_id", insertable = false, updatable = false)
    private JobQueue jobQueue;

    @Column(name = "external_id", unique = true)
    private String externalId;

    /**
     * The correlation id of the work that wrote the line (V162, MIG-94): the callback's, the dispatch's, the
     * request's. Stamped when the row is written; one string then joins the run's audit trail to every log line
     * of every service the work passed through.
     */
    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "log_detail", nullable = false, columnDefinition = "TEXT")
    private String logsDetail;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    @Column(name = "status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    /**
     * The run's tenant (V102, MIG-29/164): set when the row is written, and kept equal to the job_queue row's by the database
     * (fk_job_audit_logs_run_tenant, ON UPDATE CASCADE) -- so never written again from here. What the tenant filter scopes on.
     */
    // Not on the wire: nothing a console sends or reads names it (the wire format is unchanged).
    @JsonIgnore
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    public JobAuditLogs() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
        if (this.correlationId == null && CorrelationId.isAcceptable(CorrelationId.current())) {
            this.correlationId = CorrelationId.current();
        }
        if (this.status == null) {
            this.status = Status.Active;
        }
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Long getJobAuditLogId() {
        return jobAuditLogId;
    }

    public void setJobAuditLogId(Long jobAuditLogId) {
        this.jobAuditLogId = jobAuditLogId;
    }

    public Long getJobQueueId() {
        return jobQueueId;
    }

    public void setJobQueueId(Long jobQueueId) {
        this.jobQueueId = jobQueueId;
    }

    public JobQueue getJobQueue() {
        return jobQueue;
    }

    public String getLogsDetail() {
        return logsDetail;
    }

    public void setLogsDetail(String logsDetail) {
        this.logsDetail = logsDetail;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return EntityStrings.of(this);
    }


    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}