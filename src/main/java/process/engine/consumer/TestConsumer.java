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
import process.engine.task.HelloWorldTask;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class TestConsumer {

    public Logger logger = LogManager.getLogger(TestConsumer.class);

    @Autowired
    private HelloWorldTask helloWorldTask;
    @Autowired
    private AsyncDALTaskExecutor asyncDALTaskExecutor;

    /**
     * Consumer user to handle only test source with all-partition * for test-topic
     * */
    @KafkaListener(topics = "test-topic", clientIdPrefix = "string", groupId = "tpd-process")
    public void testConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("TestConsumer [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.helloWorldTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.helloWorldTask);
            logger.info("TestConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in testConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

}
