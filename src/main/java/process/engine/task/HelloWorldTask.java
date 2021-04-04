package process.engine.task;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import process.util.exception.ExceptionUtil;

/**
 * @author Nabeel Ahmed
 */
@Component
public class HelloWorldTask implements Runnable {

    public Logger logger = LogManager.getLogger(HelloWorldTask.class);

    private Object data;

    public HelloWorldTask() { }

    public Object getData() {
        return data;
    }
    public void setData(Object data) {
        this.data = data;
    }

    @Override
    public void run() {
        try {
            for (int i=0; i<100; i++) {
                logger.info("Number Count " + i);
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
