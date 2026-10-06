package process.model.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 * */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceTaskTypeProjection {

    Long getSourceTaskTypeId();

    String getServiceName();

    String getDescription();

    String getQueueTopicPartition();

    Status getStatus();

    Long getTotalTaskLink();

    Long getKafkaConnectionProfileId();

}
