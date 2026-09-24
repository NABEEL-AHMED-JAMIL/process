package process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.TimeZone;
import process.util.BusinessTime;
import process.util.ProcessUtil;
import process.model.pojo.LookupData;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import process.model.service.impl.TransactionServiceImpl;

/**
 * @author Nabeel Ahmed
 * */
@SpringBootApplication
public class ModelApplication {

    private Logger logger = LoggerFactory.getLogger(ModelApplication.class);

    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * A start that fails must exit non-zero (MIG-5): catching it here once made a refused boot exit 0.
     *
     * No TimeZone.setDefault (MIG-163, ADR-002). It used to tell the JVM it lived in America/Chicago, and every
     * naive timestamp in etl_job meant Chicago only because of it -- a service started without that line was
     * five or six hours wrong with no error. The columns are instants now (V100) and the business zone is named
     * where it is meant (BusinessTime), so the JVM's own zone -- UTC in the containers -- decides nothing.
     * ModelApplicationTimeZoneTest keeps it that way.
     */
    public static void main(String[] args) {
        LoggerFactory.getLogger(ModelApplication.class).info("JVM zone {} (decides nothing; business zone {})",
            TimeZone.getDefault().getID(), BusinessTime.ZONE);
        SpringApplication.run(ModelApplication.class, args);
    }

    @PostConstruct
    public void started() {
        logger.info("========== @PostConstruct started() method called ==========");
        try {
            LocalDateTime now = BusinessTime.now();
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