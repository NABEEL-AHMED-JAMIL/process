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
import process.engine.task.ScrappingTask;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class ScrappingConsumer {

    public Logger logger = LogManager.getLogger(ScrappingConsumer.class);

    @Autowired
    private ScrappingTask scrappingTask;
    @Autowired
    private AsyncDALTaskExecutor asyncDALTaskExecutor;

    /**
     * Consumer user to handle only web-scrapping source with partition = 0 for scrapping-topic
     * */
    @KafkaListener(topicPartitions = @TopicPartition(topic = "scrapping-topic", partitions = { "0" }),
        clientIdPrefix = "string", groupId = "tpd-process")
    public void webScrappingConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ScrappingConsumer web scrapping [String] received key {}: Type [{}] | Payload: {} | Record: {}",
               consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.scrappingTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.scrappingTask);
            logger.info("ExtractionConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in webScrappingConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * Consumer user to handle only data-scrapping source with partition = 1 for scrapping-topic
     * */
    @KafkaListener(topicPartitions = @TopicPartition(topic = "scrapping-topic", partitions = { "1" }),
        clientIdPrefix = "string", groupId = "tpd-process")
    public void dataScrappingConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ScrappingConsumer data scrapping [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.scrappingTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.scrappingTask);
            logger.info("ExtractionConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in dataScrappingConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * Consumer user to handle only image-scrapping source with partition = 2 for scrapping-topic
     * */
    @KafkaListener(topicPartitions = @TopicPartition(topic = "scrapping-topic", partitions = { "2" }),
        clientIdPrefix = "string", groupId = "tpd-process")
    public void imageScrappingConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("ScrappingConsumer image scrapping [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Map<String, Object> objectTransfer = new Gson().fromJson(payload, Map.class);
            this.scrappingTask.setData(objectTransfer);
            this.asyncDALTaskExecutor.addTask(this.scrappingTask);
            logger.info("ExtractionConsumer send the data to process worker thread.");
        } catch (Exception ex) {
            logger.error("Exception in imageScrappingConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

}
