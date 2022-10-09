package process.engine.task;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.engine.BulkAction;
import process.model.dto.SourceJobQueueDto;
import process.model.dto.SourceTaskDto;
import process.model.enums.JobStatus;
import process.model.parser.LoopXmlParser;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class HelloWorldTask implements Runnable {

    public Logger logger = LogManager.getLogger(HelloWorldTask.class);

    @Autowired
    private BulkAction bulkAction;
    private Map<String, ?> data;

    public HelloWorldTask() { }

    public Map<String, ?> getData() {
        return data;
    }
    public void setData(Map<String, ?> data) {
        this.data = data;
    }

    @Override
    public void run() {
        // change the status into the running status
        SourceJobQueueDto jobQueue = (SourceJobQueueDto) this.getData().get(ProcessUtil.JOB_QUEUE);
        SourceTaskDto sourceTaskDto = (SourceTaskDto) this.getData().get(ProcessUtil.TASK_DETAIL);
        try {
            this.bulkAction.changeJobLastJobRun(jobQueue.getJobId(), jobQueue.getStartTime());
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Running);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Running);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the running.", jobQueue.getJobId()));
            LoopXmlParser loopXmlParser = this.loopXmlParser(sourceTaskDto.getTaskPayload());
            // process for the current job.....
            for (int i=loopXmlParser.getStartIndex().intValue(); i<loopXmlParser.getEndIndex(); i++) {
                logger.info(String.format("Job Id %d with sub job id %d for number count %s",
                    jobQueue.getJobId(), jobQueue.getJobQueueId(), "Number Count " + i));
                //this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), "Number Count " + i);
            }
            // change the status into the complete status
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Completed);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Completed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                String.format("Job %s now complete.", jobQueue.getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
        } catch (Exception ex) {
            // change the status into the running status
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                String.format("Job %s fail due to %s .", jobQueue.getJobId(), ExceptionUtil.getRootCauseMessage(ex)));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    private LoopXmlParser loopXmlParser(String xmlPayload) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance(LoopXmlParser.class);
        Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
        LoopXmlParser loopXmlParser = (LoopXmlParser) jaxbUnmarshaller.unmarshal(new StringReader(xmlPayload));
        return loopXmlParser;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
