package process.model.repository.projection;

import process.model.enums.JobStatus;
import process.model.enums.Status;

import java.sql.Timestamp;
import java.util.Date;

public interface JobViewProjection {

    public Long getJobId();

    public String getJobName();

    public String getTriggerDetail();

    public Status getJobStatus();

    public JobStatus getJobRunningStatus();

    public Date getLastJobRun();

    public Date getNextJobRun();

    public Timestamp getDateCreated();

    public String getFrequency();

    public Integer getRunningTasks();

}
