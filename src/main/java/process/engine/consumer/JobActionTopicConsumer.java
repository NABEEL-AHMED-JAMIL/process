package process.engine.consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * @author Nabeel Ahmed
 * Class use to recived the status meesage from the sub-service
 */
@Component
public class JobActionTopicConsumer {

    public Logger logger = LogManager.getLogger(JobActionTopicConsumer.class);
}
