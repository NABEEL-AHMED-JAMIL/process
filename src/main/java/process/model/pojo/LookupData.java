package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * This LookupData use store the all scheduler detail
 * like last scheduler run
 * */
/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "lookup_data")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LookupData {

    @GenericGenerator(
        name = "lookupDataSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "lookup_id_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @GeneratedValue(generator = "lookupDataSequenceGenerator")
    private Long lookupId;

    private String lookupName;

    private String lookupType;

    private String description;

    @Column(nullable = false)
    private Timestamp dateCreated;

    public LookupData() { }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getLookupId() {
        return lookupId;
    }
    public void setLookupId(Long lookupId) {
        this.lookupId = lookupId;
    }

    public String getLookupName() {
        return lookupName;
    }
    public void setLookupName(String lookupName) {
        this.lookupName = lookupName;
    }

    public String getLookupType() {
        return lookupType;
    }
    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
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