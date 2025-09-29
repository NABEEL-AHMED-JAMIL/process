package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KafkaTaskTypeResponse {

    private Long kafkaTTId;
    private Integer numPartitions; // yes
    private String topicName; // yes
    private String topicPattern; // yes

    public KafkaTaskTypeResponse() {
    }

    public Long getKafkaTTId() {
        return kafkaTTId;
    }

    public void setKafkaTTId(Long kafkaTTId) {
        this.kafkaTTId = kafkaTTId;
    }

    public Integer getNumPartitions() {
        return numPartitions;
    }

    public void setNumPartitions(Integer numPartitions) {
        this.numPartitions = numPartitions;
    }

    public String getTopicName() {
        return topicName;
    }

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public String getTopicPattern() {
        return topicPattern;
    }

    public void setTopicPattern(String topicPattern) {
        this.topicPattern = topicPattern;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}