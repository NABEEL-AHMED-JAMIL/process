package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.JobStatus;
import java.util.Set;
/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageQSearchRequest {

    private String fromDate;
    private String toDate;
    private Set<Long> jobId;
    private Set<Long> jobQId;
    private Set<JobStatus> jobStatuses;

    public MessageQSearchRequest() {}

    public MessageQSearchRequest(String fromDate, String toDate,
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
