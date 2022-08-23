package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;

@Entity
@Table(name = "source_task_payload")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskPayload {

    @GenericGenerator(
        name = "sourceTaskPayloadSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "source_task_payload_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "task_payload_id")
    @GeneratedValue(generator = "sourceTaskPayloadSequenceGenerator")
    private Long taskPayloadId;

    @Column(name = "tag_key", nullable = true)
    private String tagKey;

    @Column(name = "tag_parent", nullable = true)
    private String tagParent;

    @Column(name = "tag_value", nullable = true)
    private String tagValue;

    public SourceTaskPayload() {
    }

    public Long getTaskPayloadId() {
        return taskPayloadId;
    }

    public void setTaskPayloadId(Long taskPayloadId) {
        this.taskPayloadId = taskPayloadId;
    }

    public String getTagKey() {
        return tagKey;
    }

    public void setTagKey(String tagKey) {
        this.tagKey = tagKey;
    }

    public String getTagParent() {
        return tagParent;
    }

    public void setTagParent(String tagParent) {
        this.tagParent = tagParent;
    }

    public String getTagValue() {
        return tagValue;
    }

    public void setTagValue(String tagValue) {
        this.tagValue = tagValue;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
