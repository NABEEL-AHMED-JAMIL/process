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
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.task.HelloWorldTask;
import process.model.dto.SourceJobQueueDto;
import process.model.dto.SourceTaskDto;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.util.HashMap;
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
    @KafkaListener(topics = "test-topic", clientIdPrefix = "string",
        groupId = "tpd-process", containerFactory = "kafkaListenerContainerFactory")
    public void testConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            Thread.sleep(500);
            logger.info("TestConsumer [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            JsonObject convertedObject = new Gson().fromJson(payload, JsonObject.class);
            Map<String, Object> taskPayloadInfo = new HashMap<>();
            taskPayloadInfo.put(ProcessUtil.JOB_QUEUE, new Gson().fromJson(
                convertedObject.get(ProcessUtil.JOB_QUEUE),SourceJobQueueDto.class));
            taskPayloadInfo.put(ProcessUtil.TASK_DETAIL, new Gson().fromJson(
                convertedObject.get(ProcessUtil.TASK_DETAIL), SourceTaskDto.class));
            this.helloWorldTask.setData(taskPayloadInfo);
            this.asyncDALTaskExecutor.addTask(this.helloWorldTask);
        } catch (InterruptedException ex) {
            logger.error("Exception in testConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
        catch (Exception ex) {
            logger.error("Exception in testConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

}
