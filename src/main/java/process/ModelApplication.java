package process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.TimeZone;
import java.time.LocalDateTime;
import process.util.ProcessUtil;
import process.model.pojo.LookupData;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import process.model.service.impl.TransactionServiceImpl;

/**
 * @author Nabeel Ahmed
 */
@EnableAsync
@EnableScheduling
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
            SpringApplication.run(ModelApplication.class, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Method run on the start to set the time
     * zone for application Karachi
     * */
    @PostConstruct
    public void started() {
        // default system timezone for application
        TimeZone.setDefault(TimeZone.getTimeZone(ProcessUtil.QATAR_TIME_ZONE));
        LocalDateTime now = LocalDateTime.now();
        LookupData obj = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
        if (obj != null) {
            obj.setLookupValue(now.toString());
            this.transactionService.updateLookupDate(obj);
        }
    }
}