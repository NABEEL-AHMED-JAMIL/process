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

    @Value("${async.task.executor.corePoolSize}")
    private Integer corePoolSize;

    @Value("${async.task.executor.maxPoolSize}")
    private Integer maxPoolSize;

    @Value("${async.task.executor.queueCapacity}")
    private Integer queueCapacity;

    @Value("${async.task.executor.keepAlive}")
    private Integer keepAlive;

    public AsyncTaskProperties() {}

    public Integer getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(Integer corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public Integer getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public Integer getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(Integer queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Integer getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(Integer keepAlive) {
        this.keepAlive = keepAlive;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}