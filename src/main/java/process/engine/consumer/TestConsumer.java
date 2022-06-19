package process.engine.consumer;

import com.google.gson.Gson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.task.HelloWorldTask;
import process.model.pojo.JobQueue;
import process.util.ProcessUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

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
        logger.info("TestConsumer [String] received key {}: Type [{}] | Payload: {} | Record: {}",
            consumerRecord.key(), typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
        Map<String, Object> objectTransfer = new HashMap<>();
        objectTransfer.put(ProcessUtil.JOB_QUEUE, new Gson().fromJson(payload, JobQueue.class));
        this.helloWorldTask.setData(objectTransfer);
        this.asyncDALTaskExecutor.addTask(this.helloWorldTask);
        logger.info("TestConsumer send the data to process worker thread.");
    }

    private String typeIdHeader(Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(header -> header.key().equals("__TypeId__"))
            .findFirst().map(header -> new String(header.value())).orElse("N/A");
    }

}
