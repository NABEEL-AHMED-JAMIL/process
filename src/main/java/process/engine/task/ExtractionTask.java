package process.engine.task;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.engine.BulkAction;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class ExtractionTask implements Runnable {

    public Logger logger = LogManager.getLogger(ExtractionTask.class);

    @Autowired
    private BulkAction bulkAction;
    private Map<String, Object> data;

    public ExtractionTask() { }

    public Map<String, Object> getData() {
        return data;
    }
    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public void run() {
        // change the status into the running status
        JobQueue jobQueue = (JobQueue) this.getData().get(ProcessUtil.JOB_QUEUE);
        try {
            this.bulkAction.changeJobLastJobRun(jobQueue.getJobId(), jobQueue.getStartTime());
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Running);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                    String.format("Job %s now in the running.", jobQueue.getJobId()));
            // process for the current job.....
            for (int i=0; i<1000; i++) {
                logger.info(String.format("Job Id %d with sub job id %d for number count %s",
                        jobQueue.getJobId(), jobQueue.getJobQueueId(), "Number Count " + i));
                //this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), "Number Count " + i);
            }
            // change the status into the complete status
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Completed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                    String.format("Job %s now complete.", jobQueue.getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
        } catch (Exception ex) {
            // change the status into the running status
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                    String.format("Job %s fail due to %s .", jobQueue.getJobId(), ExceptionUtil.getRootCauseMessage(ex)));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
