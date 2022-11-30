package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_task")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTask {

    @GenericGenerator(
        name = "taskDetailSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "task_detail_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "task_detail_id")
    @GeneratedValue(generator = "taskDetailSequenceGenerator")
    private Long taskDetailId;

    @Column(name = "task_name",
        nullable = false)
    private String taskName;

    @Column(name = "task_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status taskStatus;

    // save lob data for job detail
    @Column(name = "task_payload",
        columnDefinition = "text")
    private String taskPayload;

    @ManyToOne
    @JoinColumn(name = "source_task_type_id")
    private SourceTaskType sourceTaskType;

    @OneToMany(cascade = CascadeType.ALL)
    private List<SourceTaskPayload> sourceTaskPayload = new ArrayList<>();

    @Column(name = "user_id")
    private Long userId;

    public SourceTask() {}

    public Long getTaskDetailId() {
        return taskDetailId;
    }

    public void setTaskDetailId(Long taskDetailId) {
        this.taskDetailId = taskDetailId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Status getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(Status taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getTaskPayload() {
        return taskPayload;
    }

    public void setTaskPayload(String taskPayload) {
        this.taskPayload = taskPayload;
    }

    public SourceTaskType getSourceTaskType() {
        return sourceTaskType;
    }

    public void setSourceTaskType(SourceTaskType sourceTaskType) {
        this.sourceTaskType = sourceTaskType;
    }

    public List<SourceTaskPayload> getSourceTaskPayload() {
        return sourceTaskPayload;
    }

    public void setSourceTaskPayload(List<SourceTaskPayload> sourceTaskPayload) {
        this.sourceTaskPayload = sourceTaskPayload;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}