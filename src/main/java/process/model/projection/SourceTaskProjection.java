package process.model.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceTaskProjection {

    Long getTaskDetailId();

    String getTaskName();

    String getTaskPayload();

    Status getTaskStatus();

    String getQueueTopicPartition();

    String getServiceName();

    Long getHomePage();

    String getPipelineTaskId();

    String getGroupLabel();

}
