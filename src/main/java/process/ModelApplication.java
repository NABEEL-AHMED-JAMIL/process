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

@SpringBootApplication
public class ModelApplication {

    private Logger logger = LoggerFactory.getLogger(ModelApplication.class);

    @Autowired
    private TransactionServiceImpl transactionService;

    public static void main(String[] args) {
        try {

            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            LoggerFactory.getLogger(ModelApplication.class).info("Default TimeZone: {}", TimeZone.getDefault().getID());
            SpringApplication.run(ModelApplication.class, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @PostConstruct
    public void started() {
        logger.info("========== @PostConstruct started() method called ==========");
        try {

            ZonedDateTime znow = ZonedDateTime.now(ZoneId.of("America/Chicago"));
            LocalDateTime now = znow.toLocalDateTime();
            logger.info("=========Current Chicago Time: {} ==========", now);
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
            if (ProcessUtil.isNull(lookupData)) {

                LookupData newLookupData = new LookupData();
                newLookupData.setLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
                newLookupData.setLookupValue(now.toString());
                this.transactionService.updateLookupDate(newLookupData);
                logger.info("=========Initialized SCHEDULER_LAST_RUN_TIME to {} ==========", now);
            } else {

                logger.info("=========SCHEDULER_LAST_RUN_TIME already exists: {} (not overwriting on restart) ==========", lookupData.getLookupValue());
            }
        } catch (Exception e) {
            logger.error("=========Error in @PostConstruct started() method: {} ==========", e.getMessage(), e);
        }
    }

}