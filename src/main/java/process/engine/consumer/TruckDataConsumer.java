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
import process.engine.task.USATruckDataTask;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * @author Nabeel Ahmed
 */
@Component
public class TruckDataConsumer extends CommonConsumer {

    public Logger logger = LogManager.getLogger(TruckDataConsumer.class);

    @Autowired
    private USATruckDataTask usaTruckDataTask;


    /**
     * Consumer user to handle only truck source with all-partition * for test-topic
     * alter use can use the batch message consumer using the below one KafkaListener
     * @param consumerRecord
     * @param payload
     * */
    @KafkaListener(topics = "truck-topic", clientIdPrefix = "string",
        groupId = "tpd-process", containerFactory = "kafkaListenerContainerFactory")
    public void truckDataConsumerListener(ConsumerRecord<String, String> consumerRecord, @Payload String payload) {
        try {
            logger.info("TruckDataConsumerListener [String] received key {}: Type [{}] | Payload: {} | Record: {}",
                consumerRecord.key(), ProcessUtil.typeIdHeader(consumerRecord.headers()), payload, consumerRecord.toString());
            Thread.sleep(500);
            JsonObject convertedObject = new Gson().fromJson(payload, JsonObject.class);
            this.usaTruckDataTask.setData(this.fillTaskDetail(convertedObject));
            this.getAsyncDALTaskExecutor().addTask(this.usaTruckDataTask, convertedObject.get(ProcessUtil.PRIORITY).getAsInt());
        } catch (InterruptedException ex) {
            logger.error("Exception in TruckDataConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        } catch (Exception ex) {
            logger.error("Exception in TruckDataConsumerListener ", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

}
