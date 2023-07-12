package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Detail for scheduler
 * this class store the detail for scheduler
 * like
 * frequency => mint,hr,daily,weekly,monthly
 * date => start date to end date
 * timezone => user can set zone according the diff time zone
 * note :- end date optional if end date not define then
 * its run recurrence according to the frequency
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "scheduler")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Scheduler {

    @GenericGenerator(
        name = "schedulerSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "scheduler_source_Seq"),
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

    // mint,hr,daily,weekly,monthly
    @Column(name = "frequency",
        nullable = false)
    private String frequency;

    // mint, hr entry
    @Column(name = "recurrence")
    private String recurrence;

    @Column(name = "recurrence_time")
    private LocalDateTime recurrenceTime;

    @Column(name = "time_zone_id", nullable = false)
    private String timeZone;

    @OneToOne
    @MapsId
    @JoinColumn(name = "job_id", nullable = false)
    private SourceJob sourceJob;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    public Scheduler() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
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

    public String getRecurrence() {
        return recurrence;
    }

    public void setRecurrence(String recurrence) {
        this.recurrence = recurrence;
    }

    public LocalDateTime getRecurrenceTime() {
        return recurrenceTime;
    }

    public void setRecurrenceTime(LocalDateTime recurrenceTime) {
        this.recurrenceTime = recurrenceTime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public SourceJob getSourceJob() {
        return sourceJob;
    }

    public void setSourceJob(SourceJob sourceJob) {
        this.sourceJob = sourceJob;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}