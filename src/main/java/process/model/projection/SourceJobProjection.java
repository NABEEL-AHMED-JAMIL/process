package process.model.projection;

import process.model.enums.JobStatus;
import process.model.enums.Status;
import java.sql.Timestamp;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceJobProjection {

    Long getJobId();

    Status getJobStatus();

    JobStatus getJobRunningStatus();

    /** The instant; BusinessTime.wallClockOf gives what the event carries. */
    Timestamp getLastJobRun();

    String getNextRunAt();

    String getExecution();

    String getAssignedUsername();

    Long getAssignedUserId();

    Long getTenantId();

    String getJobName();

}
