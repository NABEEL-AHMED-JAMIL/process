package process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import process.util.ProcessUtil;
import process.model.pojo.LookupData;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import process.model.service.impl.TransactionServiceImpl;

/**
 * @author Nabeel Ahmed
 */
@SpringBootApplication
public class ModelApplication {

    private Logger logger = LoggerFactory.getLogger(ModelApplication.class);

    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * Method run the application
     * @param args
     * */
    public static void main(String[] args) {
        try {
            // set application default timezone to America/Chicago so all LocalDate/Time operations
            // that rely on the system default will use the Chicago timezone
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            LoggerFactory.getLogger(ModelApplication.class).info("Default TimeZone: {}", TimeZone.getDefault().getID());
            SpringApplication.run(ModelApplication.class, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Method run on the start to set the time
     * */
    @PostConstruct
    public void started() {
        logger.info("========== @PostConstruct started() method called ==========");
        try {
            // default system timezone for application
            // use ZonedDateTime with Chicago zone to be explicit when storing scheduler last run
            ZonedDateTime znow = ZonedDateTime.now(ZoneId.of("America/Chicago"));
            LocalDateTime now = znow.toLocalDateTime();
            logger.info("=========Current Chicago Time: {} ==========", now);
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
            if (!ProcessUtil.isNull(lookupData)) {
                lookupData.setLookupValue(now.toString());
                this.transactionService.updateLookupDate(lookupData);
                logger.info("=========Updated SCHEDULER_LAST_RUN_TIME to {} ==========", now);
            } else {
                logger.warn("=========SCHEDULER_LAST_RUN_TIME lookup_data is NULL ==========");
            }
        } catch (Exception e) {
            logger.error("=========Error in @PostConstruct started() method: {} ==========", e.getMessage(), e);
        }
    }

}