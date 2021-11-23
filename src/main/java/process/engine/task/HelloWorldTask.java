package process.engine.task;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import process.engine.BulkAction;
import process.model.enums.JobStatus;
import process.model.pojo.JobHistory;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
@Scope("prototype")
public class HelloWorldTask implements Runnable {

    public Logger logger = LogManager.getLogger(HelloWorldTask.class);

    @Autowired
    private BulkAction bulkAction;
    private Map<String, Object> data;

    public HelloWorldTask() { }

    public Map<String, Object> getData() {
        return data;
    }
    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public void run() {
        // change the status into the running status
        JobHistory jobHistory = (JobHistory) this.getData().get("jobHistory");
        try {
            this.bulkAction.changeJobLastJobRun(jobHistory.getJobId(), jobHistory.getStartTime());
            this.bulkAction.changeJobHistoryStatus(jobHistory.getJobHistoryId(), JobStatus.Running);
            this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(),
                String.format("Job %s now in the running.", jobHistory.getJobId()));
            // process for the current job.....
            for (int i=0; i<1000; i++) {
                this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(), "Number Count " + i);
            }
            // change the status into the complete status
            this.bulkAction.changeJobHistoryStatus(jobHistory.getJobHistoryId(), JobStatus.Completed);
            this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(),
                String.format("Job %s now complete.", jobHistory.getJobId()));
            this.bulkAction.changeJobHistoryEndDate(jobHistory.getJobHistoryId(), LocalDateTime.now());
        } catch (Exception ex) {
            // change the status into the running status
            this.bulkAction.changeJobHistoryStatus(jobHistory.getJobHistoryId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobHistory.getJobId(), jobHistory.getJobHistoryId(),
                String.format("Job %s fail due to %s .", jobHistory.getJobId(), ExceptionUtil.getRootCauseMessage(ex)));
            this.bulkAction.changeJobHistoryEndDate(jobHistory.getJobHistoryId(), LocalDateTime.now());
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
