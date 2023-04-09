package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import process.model.enums.TaskType;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "source_task_type")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceTaskType {

    @GenericGenerator(
        name = "sourceTaskTypeSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "source_task_type_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="source_task_type_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sourceTaskTypeSequenceGenerator")
    private Long sourceTaskTypeId;

    @Column(name = "service_name",
        nullable = false)
    private String serviceName;

    @Column(name = "description",
         nullable = false)
    private String description;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "task_type", nullable = false,
        updatable = false)
    private TaskType taskType;

    @OneToMany(mappedBy = "sourceTaskType")
    private List<ApiTaskType> apiTaskType;

    @OneToMany(mappedBy = "sourceTaskType")
    private List<KafkaTaskType> kafkaTaskType;

    @Column(name = "task_type_status",
            nullable = false)
    @Enumerated(EnumType.ORDINAL)
    private Status status;

    /**default source task type for testing
     * Test Loop
     * if any other*/
    @Column(name = "is_default",
        nullable = false)
    private Boolean isDefault;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @ManyToMany(cascade = {
        CascadeType.PERSIST, CascadeType.MERGE
    }, fetch = FetchType.LAZY)
    @JoinTable(	name = "app_user_sttf",
        joinColumns = @JoinColumn(name = "stt_id"),
        inverseJoinColumns = @JoinColumn(name = "sttf_id"))
    private Set<STTForm> appUserSTTForms = new HashSet<>();

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    public SourceTaskType() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getSourceTaskTypeId() {
        return sourceTaskTypeId;
    }

    public void setSourceTaskTypeId(Long sourceTaskTypeId) {
        this.sourceTaskTypeId = sourceTaskTypeId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public List<ApiTaskType> getApiTaskType() {
        return apiTaskType;
    }

    public void setApiTaskType(List<ApiTaskType> apiTaskType) {
        this.apiTaskType = apiTaskType;
    }

    public List<KafkaTaskType> getKafkaTaskType() {
        return kafkaTaskType;
    }

    public void setKafkaTaskType(List<KafkaTaskType> kafkaTaskType) {
        this.kafkaTaskType = kafkaTaskType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Boolean isDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Set<STTForm> getAppUserSTTForms() {
        return appUserSTTForms;
    }

    public void setAppUserSTTForms(Set<STTForm> appUserSTTForms) {
        this.appUserSTTForms = appUserSTTForms;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
