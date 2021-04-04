package process.engine.task;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import process.util.exception.ExceptionUtil;

@Component
public class StoreLocatorPinTask implements Runnable {

    public Logger logger = LogManager.getLogger(StoreLocatorPinTask.class);

    private Object data;

    public StoreLocatorPinTask() { }

    public Object getData() {
        return data;
    }
    public void setData(Object data) {
        this.data = data;
    }

    @Override
    public void run() {
        try {
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
