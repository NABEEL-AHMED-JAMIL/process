package process.engine.async.properties;

import com.google.gson.Gson;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * @author Nabeel Ahmed
 */
@Component
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AsyncTaskProperties {

    @Value("${async.task.executor.minThreads}")
    private Integer minThreads;
    @Value("${async.task.executor.maxThreads}")
    private Integer maxThreads;
    @Value("${async.task.executor.idleThreadLife}")
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