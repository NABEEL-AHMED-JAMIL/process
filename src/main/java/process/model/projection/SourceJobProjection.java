package process.model.projection;

import process.model.enums.JobStatus;
import process.model.enums.Status;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceJobProjection {

    public Long getJobId();

    public Status getJobStatus();

    public JobStatus getJobRunningStatus();

    public LocalDateTime getLastJobRun();

    public String getRecurrenceTime();

    public String getExecution();

    /** Who to push this job's live status to over WebSocket -- null if the job predates
     * assignedUserId or its assignee has since been deleted. See BulkAction.sendJobStatusNotification. */
    public String getAssignedUsername();

}
