package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.JobStatus;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageQSearchDto {

    private String fromDate;
    private String toDate;
    private Set<Long> jobId;
    private Set<Long> jobQId;
    private Set<JobStatus> jobStatuses;
    /** How many runs one read hands over, newest first (scale review P0 #3); the service's default and ceiling apply. */
    private Integer limit;
    /** Which window of {@code limit} runs, from 0. */
    private Integer page;
    /** UX review 2026-10-13 (B3): only the runs of these pipelines (source_task.task_detail_id, through the run's schedule). */
    private Set<Long> taskDetailIds;
    /** UX review 2026-10-13 (B3): only the runs started this way -- RunTriggers' keys (manual, file, document, retry, api, schedule). */
    private Set<String> triggers;
    /**
     * Owner 2026-10-27 ("add a sort on each table"): the rows' order, a column of RunListSort (run, queued, started, ended,
     * duration, status) and asc/desc; none is newest first, as before. The counts are not affected.
     */
    private String sort;
    private String dir;

    public MessageQSearchDto() {}

    public MessageQSearchDto(String fromDate, String toDate,
        Set<Long> jobId, Set<Long> jobQId, Set<JobStatus> jobStatuses) {
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.jobId = jobId;
        this.jobQId = jobQId;
        this.jobStatuses = jobStatuses;
    }

    public String getFromDate() {
        return fromDate;
    }

    public void setFromDate(String fromDate) {
        this.fromDate = fromDate;
    }

    public String getToDate() {
        return toDate;
    }

    public void setToDate(String toDate) {
        this.toDate = toDate;
    }

    public Set<Long> getJobId() {
        return jobId;
    }

    public void setJobId(Set<Long> jobId) {
        this.jobId = jobId;
    }

    public Set<Long> getJobQId() {
        return jobQId;
    }

    public void setJobQId(Set<Long> jobQId) {
        this.jobQId = jobQId;
    }

    public Set<JobStatus> getJobStatuses() {
        return jobStatuses;
    }

    public void setJobStatuses(Set<JobStatus> jobStatuses) {
        this.jobStatuses = jobStatuses;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Set<Long> getTaskDetailIds() {
        return taskDetailIds;
    }

    public void setTaskDetailIds(Set<Long> taskDetailIds) {
        this.taskDetailIds = taskDetailIds;
    }

    public Set<String> getTriggers() {
        return triggers;
    }

    public void setTriggers(Set<String> triggers) {
        this.triggers = triggers;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
