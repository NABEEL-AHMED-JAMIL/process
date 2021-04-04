package process;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import javax.annotation.PostConstruct;
import java.util.TimeZone;

/**
 * @author Nabeel Ahmed
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class ModelApplication {

    /**
     * Method run the application
     * */
    public static void main(String[] args) {
        SpringApplication.run(ModelApplication.class, args);
    }

    /**
     * Method run on the start to set the time
     * zone for application
     * */
    @PostConstruct
    public void started() {
        // default system timezone for application
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Karachi"));
    }

}