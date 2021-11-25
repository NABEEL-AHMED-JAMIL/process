package process;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import process.model.pojo.LookupData;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessUtil;
import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.TimeZone;

/**
 * @author Nabeel Ahmed
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class ModelApplication {

    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * Method run the application
     * @param args
     * */
    public static void main(String[] args) {
        SpringApplication.run(ModelApplication.class, args);
    }

    /**
     * Method run on the start to set the time
     * zone for application Karachi
     * */
    @PostConstruct
    public void started() {
        // default system timezone for application
        TimeZone.setDefault(TimeZone.getTimeZone(ProcessUtil.TIME_ZONE));
        LocalDateTime now = LocalDateTime.now();
        LookupData obj = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
        obj.setLookupName(now.toString());
        this.transactionService.updateLookupDate(obj);
    }
}