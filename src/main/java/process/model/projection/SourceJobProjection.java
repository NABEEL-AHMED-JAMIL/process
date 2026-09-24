package process.model.projection;

import process.model.enums.JobStatus;
import process.model.enums.Status;
import java.sql.Timestamp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceJobProjection {

    public Long getJobId();

    public Status getJobStatus();

    public JobStatus getJobRunningStatus();

    /** The instant; BusinessTime.wallClockOf gives what the event carries. */
    public Timestamp getLastJobRun();

    public String getNextRunAt();

    public String getExecution();

    public String getAssignedUsername();

    public Long getAssignedUserId();

    public Long getTenantId();

    public String getJobName();

}
