package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
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

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Column(name = "status",nullable = false)
    private Long status;

    @ManyToOne
    @JoinColumn(name = "stt_id")
    private STT stt;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @OneToMany(mappedBy = "sourceTask")
    private List<SourceTaskData> sourceTaskData = new ArrayList<>();

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

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public STT getStt() {
        return stt;
    }

    public void setStt(STT stt) {
        this.stt = stt;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public List<SourceTaskData> getSourceTaskData() {
        return sourceTaskData;
    }

    public void setSourceTaskData(List<SourceTaskData> sourceTaskData) {
        this.sourceTaskData = sourceTaskData;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}