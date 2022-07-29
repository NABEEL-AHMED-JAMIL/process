package process.model.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public interface SourceTaskTypeProjection {

    public Long getSourceTaskTypeId();

    public String getServiceName();

    public String getDescription();

    public String getQueueTopicPartition();

    public Status getStatus();

    public Long getTotalTaskLink();

}
