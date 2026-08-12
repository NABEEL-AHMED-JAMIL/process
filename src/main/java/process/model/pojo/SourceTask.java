package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;
import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_task", indexes = {
    @Index(name = "idx_source_task_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
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

    /** Owning tenant -- see Tenant/TenantContext. Nullable during the Phase 0 migration window
     * (backfilled to the Default tenant by TenantSeedService on startup). */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "task_name",
        nullable = false)
    private String taskName;

    @Column(name = "task_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status taskStatus;

    @Column(name = "home_page_id")
    private String homePageId;

    /**
     * pipeline id use to move the data to the
     * right path if kafka topic using for multiple pipeline
     * */
    @Column(name = "pipeline_id")
    private String pipelineId;

    /**
     * group this task belongs to -- same "lookup_id as varchar" convention as homePageId/
     * pipelineId (a TASK_GROUPS parent lookup's child lookup_id), so tasks can be organized
     * and browsed by group in the Task List and the Link Source Task picker.
     * */
    @Column(name = "group_id")
    private String groupId;

    // save lob data for job detail
    @Column(name = "task_payload",
        columnDefinition = "text")
    private String taskPayload;

    @ManyToOne
    @JoinColumn(name = "source_task_type_id")
    private SourceTaskType sourceTaskType;

    @JoinColumn(name = "payload_id")
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SourceTaskPayload> sourceTaskPayload = new ArrayList<>();

    public SourceTask() {}

    public Long getTaskDetailId() {
        return taskDetailId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
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

    public String getHomePageId() {
        return homePageId;
    }

    public void setHomePageId(String homePageId) {
        this.homePageId = homePageId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
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
        this.sourceTaskPayload.clear();
        this.sourceTaskPayload.addAll(sourceTaskPayload);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}