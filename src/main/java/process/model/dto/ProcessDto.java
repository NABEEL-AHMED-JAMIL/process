package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProcessDto {

    private JobDto job;
    private SchedulerDto scheduler;

    public ProcessDto() { }

    public JobDto getJob() {
        return job;
    }
    public void setJob(JobDto job) {
        this.job = job;
    }

    public SchedulerDto getScheduler() {
        return scheduler;
    }
    public void setScheduler(SchedulerDto scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
