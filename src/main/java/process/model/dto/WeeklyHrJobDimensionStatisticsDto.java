package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WeeklyHrJobDimensionStatisticsDto {

    private Long jobId;
    private String jobName;
    private Long queue;
    private Long running;
    private Long failed;
    private Long completed;
    private Long stop;
    private Long skip;
    private Long total;

    public WeeklyHrJobDimensionStatisticsDto() {}

    public WeeklyHrJobDimensionStatisticsDto(Long jobId, String jobName,
        Long queue, Long running, Long failed, Long completed,
        Long stop, Long skip, Long total) {
        this.jobId = jobId;
        this.jobName = jobName.equals("null") ? "Total Count" : jobName;
        this.queue = queue;
        this.running = running;
        this.failed = failed;
        this.completed = completed;
        this.stop = stop;
        this.skip = skip;
        this.total = total;
    }

    public WeeklyHrJobDimensionStatisticsDto(Long queue, Long running, Long failed,
        Long completed, Long stop, Long skip, Long total) {
        this.queue = queue;
        this.running = running;
        this.failed = failed;
        this.completed = completed;
        this.stop = stop;
        this.skip = skip;
        this.total = total;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public Long getQueue() {
        return queue;
    }

    public void setQueue(Long queue) {
        this.queue = queue;
    }

    public Long getRunning() {
        return running;
    }

    public void setRunning(Long running) {
        this.running = running;
    }

    public Long getFailed() {
        return failed;
    }

    public void setFailed(Long failed) {
        this.failed = failed;
    }

    public Long getCompleted() {
        return completed;
    }

    public void setCompleted(Long completed) {
        this.completed = completed;
    }

    public Long getStop() {
        return stop;
    }

    public void setStop(Long stop) {
        this.stop = stop;
    }

    public Long getSkip() {
        return skip;
    }

    public void setSkip(Long skip) {
        this.skip = skip;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
