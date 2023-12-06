package process.engine.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.emailer.EmailMessagesFactory;
import process.engine.BulkAction;
import com.google.gson.Gson;
import process.engine.parser.TestLoop;
import process.model.dto.SourceJobQueueDto;
import process.model.dto.SourceTaskDto;
import process.model.enums.JobStatus;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessUtil;
import process.util.XmlOutTagInfoUtil;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.Map;


@Component
public class TestLoopTask implements Runnable {

    public Logger logger = LogManager.getLogger(TestLoopTask.class);

    private Map<String, Object> data;
    @Autowired
    private BulkAction bulkAction;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private EmailMessagesFactory emailMessagesFactory;
    @Autowired
    private XmlOutTagInfoUtil xmlOutTagInfoUtil;

    public TestLoopTask() {
    }

    /**
     * Method use to process the data
     * */
    @Override
    public void run() {
        // change the status into the running status
        SourceJobQueueDto jobQueue = (SourceJobQueueDto) this.getData().get(ProcessUtil.JOB_QUEUE);
        SourceTaskDto sourceTaskDto = (SourceTaskDto) this.getData().get(ProcessUtil.TASK_DETAIL);
        try {
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Running);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Running);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the running.", jobQueue.getJobId()));
            this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue(), (String)this.getData().get(ProcessUtil.TRANSACTION_ID));
            TestLoop testLoop = xmlOutTagInfoUtil.convertXMLToObject(sourceTaskDto.getTaskPayload(), TestLoop.class);
            // process for the current job.....
            for (int i=testLoop.getStart(); i<testLoop.getEnd(); i++) {
                logger.info(String.format("Job Id %d with sub job id %d for number count %s",
                     jobQueue.getJobId(), jobQueue.getJobQueueId(), "Number Count " + i));
            }
            // change the status into the complete status
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Completed);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Completed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now complete.", jobQueue.getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
            this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue(), (String)this.getData().get(ProcessUtil.TRANSACTION_ID));
            if (this.transactionService.findByJobId(jobQueue.getJobId()).get().isCompleteJob()) {
                this.emailMessagesFactory.sendSourceJobEmail(jobQueue,JobStatus.Completed);
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            // change the status into the fail status
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s fail due to %s .",
                 jobQueue.getJobId(), ExceptionUtil.getRootCauseMessage(ex)));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
            this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue(), (String)this.getData().get(ProcessUtil.TRANSACTION_ID));
            if (this.transactionService.findByJobId(jobQueue.getJobId()).get().isFailJob()) {
                this.emailMessagesFactory.sendSourceJobEmail(jobQueue,JobStatus.Failed);
            }
        }
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
