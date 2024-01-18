package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "stt")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STT {

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
    @Column(name="stt_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sourceTaskTypeSequenceGenerator")
    private Long sttId;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "task_type",
        nullable = false, updatable = false)
    private Long taskType;

    @OneToMany(mappedBy = "stt")
    private List<ApiTaskType> apiTaskType = new ArrayList<>();

    @OneToMany(mappedBy = "stt")
    private List<KafkaTaskType> kafkaTaskType = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name="credential_id")
    private Credential credential;

    @Column(name = "status", nullable = false)
    private Long status;

    /**default source task type for testing
     * Test Loop
     * if any other*/
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;

    @OneToMany(mappedBy="stt")
    private List<AppUserSTT> appUserSTT = new ArrayList<>();

    @OneToMany(mappedBy="stt")
    private List<SourceTask> sourceTasks = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public STT() {}

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getSttId() {
        return sttId;
    }

    public void setSttId(Long sttId) {
        this.sttId = sttId;
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

    public Long getTaskType() {
        return taskType;
    }

    public void setTaskType(Long taskType) {
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

    public Credential getCredential() {
        return credential;
    }

    public void setCredential(Credential credential) {
        this.credential = credential;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public List<AppUserSTT> getAppUserSTT() {
        return appUserSTT;
    }

    public void setAppUserSTT(List<AppUserSTT> appUserSTT) {
        this.appUserSTT = appUserSTT;
    }

    public List<SourceTask> getSourceTasks() {
        return sourceTasks;
    }

    public void setSourceTasks(List<SourceTask> sourceTasks) {
        this.sourceTasks = sourceTasks;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
