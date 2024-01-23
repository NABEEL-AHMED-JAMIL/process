package process.engine.consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import process.engine.task.TestLoopTask;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * @author Nabeel Ahmed
 */
@Component
public class TestConsumer extends CommonConsumer {

    public Logger logger = LogManager.getLogger(TestConsumer.class);

    @Autowired
    private TestLoopTask helloWorldTask;

    /**
     * Consumer user to handle only test source with all-partition * for test-topic
     * alter use can use the batch message consumer using the below one KafkaListener
     * @param consumerRecord
     * @param payload
     * */
    @KafkaListener(topics = "test-topic", clientIdPrefix = "string", groupId = "tpd-process",
        containerFactory = "kafkaListenerContainerFactory")
    public void testConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("TestConsumerListener [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Thread.sleep(1000);
            JsonObject convertedObject = new Gson().fromJson(payload, JsonObject.class);
            this.helloWorldTask.setData(this.fillTaskDetail(convertedObject));
            this.getAsyncDALTaskExecutor().addTask(this.helloWorldTask, convertedObject.get(ProcessUtil.PRIORITY).getAsInt());
        } catch (InterruptedException ex) {
            logger.error("Exception in TestConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        } catch (Exception ex) {
            logger.error("Exception in TestConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }
}
