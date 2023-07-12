package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_task_data")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskData {

    @GenericGenerator(
        name = "sourceTaskDataSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "source_task_data_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "task_data_id")
    @GeneratedValue(generator = "sourceTaskDataSequenceGenerator")
    private Long taskDataId;

    @ManyToOne
    @JoinColumn(name = "task_detail_id")
    private SourceTask sourceTask;

    @Column(name = "status", nullable = false)
    private Long status;

    public SourceTaskData() {
    }

    public Long getTaskDataId() {
        return taskDataId;
    }

    public void setTaskDataId(Long taskDataId) {
        this.taskDataId = taskDataId;
    }

    public SourceTask getSourceTask() {
        return sourceTask;
    }

    public void setSourceTask(SourceTask sourceTask) {
        this.sourceTask = sourceTask;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
