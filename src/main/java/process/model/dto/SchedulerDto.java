package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

import javax.persistence.Column;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerDto {

    private Long schedulerId;

    private LocalDate startDate;

    private LocalDate endDate;

    private LocalTime startTime;

    // mint,hr,daily,weekly,monthly
    private String frequency;

    // mint, hr entry
    private String recurrence;

    // like:- email notification job, so other job ect.
    private Long jobId;

    private Timestamp dateCreated;

    private LocalDateTime recurrenceTime;

    public SchedulerDto() {}

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
