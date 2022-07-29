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
