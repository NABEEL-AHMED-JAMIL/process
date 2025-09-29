package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "env_variables")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnvVariables {

    @GenericGenerator(
        name = "envVariableSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @org.hibernate.annotations.Parameter(name = "sequence_name", value = "au_env_Seq"),
            @org.hibernate.annotations.Parameter(name = "initial_value", value = "1000"),
            @org.hibernate.annotations.Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "env_key_id")
    @GeneratedValue(generator = "envVariableSequenceGenerator")
    private Long envKeyId;

    @Column(name = "env_key", nullable = false)
    private String envKey;

    @Column(name = "status", nullable = false)
    private Long status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;

    public EnvVariables() {
    }

    public Long getEnvKeyId() {
        return envKeyId;
    }

    public void setEnvKeyId(Long envKeyId) {
        this.envKeyId = envKeyId;
    }

    public String getEnvKey() {
        return envKey;
    }

    public void setEnvKey(String envKey) {
        this.envKey = envKey;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(AppUser createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
