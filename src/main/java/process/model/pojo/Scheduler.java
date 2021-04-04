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
    @GeneratedValue(generator = "schedulerSequenceGenerator")
    private Long schedulerId;

    @Column(nullable = false, columnDefinition = "DATE")
    private LocalDate startDate;

    @Column(columnDefinition = "DATE")
    private LocalDate endDate;

    @Column(nullable = false, columnDefinition = "TIME")
    private LocalTime startTime;

    // mint,hr,daily,weekly,monthly
    @Column(nullable = false)
    private String frequency;

    // mint, hr entry
    private String recurrence;

    // like:- email notification job, so other job ect.
    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false)
    private Timestamp dateCreated;

    private LocalDateTime recurrenceTime;

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

    public Long getJobId() {
        return jobId;
    }
    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }
    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public LocalDateTime getRecurrenceTime() {
        return recurrenceTime;
    }
    public void setRecurrenceTime(LocalDateTime recurrenceTime) {
        this.recurrenceTime = recurrenceTime;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
