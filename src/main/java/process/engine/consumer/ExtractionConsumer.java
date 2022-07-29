package process.engine.consumer;

import com.google.gson.Gson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.task.ExtractionTask;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class ExtractionConsumer {

    public Logger logger = LogManager.getLogger(ExtractionConsumer.class);

    @Autowired
    private ExtractionTask extractionTask;
    @Autowired
    private AsyncDALTaskExecutor asyncDALTaskExecutor;

    /**
     * Consumer user to handle only extraction source with all-partition * for test-topic
     * */
    @KafkaListener(topics = "extraction-topic", clientIdPrefix = "string", groupId = "tpd-process")
    public void extractionConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ExtractionConsumer extraction [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.extractionTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.extractionTask);
            logger.info("ExtractionConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in extractionConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

}
