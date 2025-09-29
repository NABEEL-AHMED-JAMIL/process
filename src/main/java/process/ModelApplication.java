package process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import process.util.ProcessUtil;
import process.model.pojo.LookupData;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author Nabeel Ahmed
 */
@SpringBootApplication
public class ModelApplication {

    private Logger logger = LoggerFactory.getLogger(ModelApplication.class);

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
     * */
    @PostConstruct
    public void started() {
        // default system timezone for application
        LocalDateTime now = LocalDateTime.now();
        LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
        if (!ProcessUtil.isNull(lookupData)) {
            lookupData.setLookupValue(now.toString());
            this.transactionService.updateLookupDate(lookupData);
        }
    }

}