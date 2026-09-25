package process.model.pojo;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * @author Nabeel Ahmed
 * */
@Entity
@Table(name = "scheduler")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Scheduler {

    @GenericGenerator(
        name = "schedulerSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "scheduler_source_seq"),
            @Parameter(name = "initial_value", value = "1001"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "scheduler_id")
    @GeneratedValue(generator = "schedulerSequenceGenerator")
    private Long schedulerId;

    @Column(name = "start_date",
        nullable = false,
        columnDefinition = "DATE")
    private LocalDate startDate;

    @Column(name = "end_date",
        columnDefinition = "DATE")
    private LocalDate endDate;

    @Column(name = "start_time",
        nullable = false,
        columnDefinition = "TIME")
    private LocalTime startTime;

    @Column(name = "frequency",
        nullable = false)
    private String frequency;

    @Column(name = "interval_value")
    private String intervalValue;

    @Column(name = "days_of_week")
    private String daysOfWeek;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "job_id",
        nullable = false)
    private Long jobId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", insertable = false, updatable = false)
    private SourceJob sourceJob;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    @Column(name = "date_updated")
    private Timestamp dateUpdated;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "expired", nullable = false)
    private boolean expired;

    /**
     * The workspace pause (workspace_directory.status_since) this schedule has already written its audit line
     * for (V163): one line per pause, not per skipped slot. Null when not paused. Core's own bookkeeping.
     */
    @JsonIgnore
    @Column(name = "paused_since", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Timestamp pausedSince;

    /**
     * The job's tenant (V102, MIG-29/164): set when the row is written, and kept equal to the source_job row's by the database
     * (fk_scheduler_job_tenant, ON UPDATE CASCADE) -- so never written again from here. What the tenant filter scopes on.
     */
    // Not on the wire: nothing a console sends or reads names it (the wire format is unchanged).
    @JsonIgnore
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    public Scheduler() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
        this.dateUpdated = this.dateCreated;
    }

    @PreUpdate
    protected void onUpdate() {
        this.dateUpdated = new Timestamp(System.currentTimeMillis());
    }

    public Long getSchedulerId() {
        return schedulerId;
    }

    public void setSchedulerId(Long schedulerId) {
        this.schedulerId = schedulerId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getIntervalValue() {
        return intervalValue;
    }

    public void setIntervalValue(String intervalValue) {
        this.intervalValue = intervalValue;
    }

    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(String daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public Integer getDayOfMonth() {
        return dayOfMonth;
    }

    public void setDayOfMonth(Integer dayOfMonth) {
        this.dayOfMonth = dayOfMonth;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public SourceJob getSourceJob() {
        return sourceJob;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Timestamp getDateUpdated() {
        return dateUpdated;
    }

    public void setDateUpdated(Timestamp dateUpdated) {
        this.dateUpdated = dateUpdated;
    }

    public LocalDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(LocalDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public boolean isExpired() {
        return expired;
    }

    public void setExpired(boolean expired) {
        this.expired = expired;
    }

    public Timestamp getPausedSince() {
        return pausedSince;
    }

    public void setPausedSince(Timestamp pausedSince) {
        this.pausedSince = pausedSince;
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