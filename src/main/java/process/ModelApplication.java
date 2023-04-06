package process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author Nabeel Ahmed
 */
@EnableAsync
@EnableScheduling
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

}