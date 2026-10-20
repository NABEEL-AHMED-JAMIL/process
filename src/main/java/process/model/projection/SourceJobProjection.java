package process.model.projection;

import process.model.enums.JobStatus;
import process.model.enums.Status;
import java.time.Instant;
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
    /** Hibernate 6+ answers the timestamptz column as an Instant (MIG-389); the same instant Hibernate 5's Timestamp held. */
    Instant getLastJobRun();

    String getNextRunAt();

    String getExecution();

    String getAssignedUsername();

    Long getAssignedUserId();

    Long getTenantId();

    String getJobName();

}
