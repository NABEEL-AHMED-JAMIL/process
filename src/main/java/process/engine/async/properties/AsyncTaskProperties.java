package process.engine.async.properties;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @author Nabeel Ahmed
 */
@Component
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AsyncTaskProperties {

    @Value("${asyncetaskexecutor.minThreads}")
    private Integer minThreads;
    @Value("${asyncetaskexecutor.maxThreads}")
    private Integer maxThreads;
    @Value("${asyncetaskexecutor.idleThreadLife}")
    private Integer idleThreadLife;

    public AsyncTaskProperties() {}

    public Integer getMinThreads() {
        return minThreads;
    }
    public void setMinThreads(Integer minThreads) {
        this.minThreads = minThreads;
    }

    public Integer getMaxThreads() {
        return maxThreads;
    }
    public void setMaxThreads(Integer maxThreads) {
        this.maxThreads = maxThreads;
    }

    public Integer getIdleThreadLife() {
        return idleThreadLife;
    }
    public void setIdleThreadLife(Integer idleThreadLife) {
        this.idleThreadLife = idleThreadLife;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}