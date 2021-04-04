package process.engine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.model.dto.JobHistoryDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.Job;
import process.model.pojo.JobHistory;
import process.model.service.impl.TransactionServiceImpl;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Component
public class BulkAction {

    public Logger logger = LogManager.getLogger(BulkAction.class);

    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * This method use the change the status of main job
     * @param jobId
     * @param jobStatus
     * */
    public void changeJobStatus(Long jobId, JobStatus jobStatus) {
        Optional<Job> job = this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active);
        job.get().setJobRunningStatus(jobStatus);
        this.transactionService.updateJobStatus(job.get());
    }

    /**
     * This method use to run the last job in the main job
     * @param jobId
     * @param lastJobRun
     * */
    public void changeJobLastJobRun(Long jobId, LocalDateTime lastJobRun) {
        Optional<Job> job = this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active);
        job.get().setLastJobRun(lastJobRun);
        this.transactionService.updateJobStatus(job.get());
    }

    /**
     * This method use to add the job into the job-history in the queue state
     * the schedule pick the job from the job-history and push into the queue
     * @param jobId
     * @param scheduledTime
     * @return JobHistoryDto
     * */
    public JobHistoryDto createJobHistory(Long jobId, LocalDateTime scheduledTime) {
        JobHistoryDto jobHistoryDto = new JobHistoryDto();
        jobHistoryDto.setStartTime(scheduledTime);
        jobHistoryDto.setJobStatus(JobStatus.Queue);
        jobHistoryDto.setJobId(jobId);
        jobHistoryDto.setJobStatusMessage(String.format("Job %s now in the queue.", jobId));
        return this.transactionService.saveJobHistory(jobHistoryDto);
    }
}
