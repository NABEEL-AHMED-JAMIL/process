package process.engine.consumer;

import com.google.gson.Gson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.TopicPartition;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.task.ComparisonTask;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class ComparisonConsumer {

    public Logger logger = LogManager.getLogger(ComparisonConsumer.class);

    @Autowired
    private ComparisonTask comparisonTask;
    @Autowired
    private AsyncDALTaskExecutor asyncDALTaskExecutor;

    /**
     * Consumer user to handle only web-comparison source with partition = 0 for comparison-topic
     * */
    @KafkaListener(topicPartitions = @TopicPartition(topic = "comparison-topic", partitions = { "0" }),
        clientIdPrefix = "string", groupId = "tpd-process")
    public void webComparisonConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ComparisonConsumer web comparison [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.comparisonTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.comparisonTask);
            logger.info("ComparisonConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in webComparisonConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * Consumer user to handle only data-comparison source with partition = 1 for comparison-topic
     * */
    @KafkaListener(topicPartitions = @TopicPartition(topic = "comparison-topic", partitions = { "1" }),
        clientIdPrefix = "string", groupId = "tpd-process")
    public void dataComparisonConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ComparisonConsumer web comparison [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                 consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.comparisonTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.comparisonTask);
            logger.info("ComparisonConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in dataComparisonConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * Consumer user to handle only data-comparison source with partition = 2 for comparison-topic
     * */
    @KafkaListener(topicPartitions = @TopicPartition(topic = "comparison-topic", partitions = { "2" }),
        clientIdPrefix = "string", groupId = "tpd-process")
    public void imageComparisonConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ComparisonConsumer web comparison [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.comparisonTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.comparisonTask);
            logger.info("ComparisonConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in imageComparisonConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

}
