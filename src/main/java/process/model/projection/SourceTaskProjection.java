package process.model.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceTaskProjection {

    public Long getTaskDetailId();

    public String getTaskName();

    public String getTaskPayload();

    public Status getTaskStatus();

    public String getQueueTopicPartition();

    public String getServiceName();

}
