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
@Table(name = "stt_control")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTControl {

    @GenericGenerator(
        name = "sttControlSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "sttControl_source_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name="sttc_id", unique=true, nullable=false)
    @GeneratedValue(generator = "sttControlSequenceGenerator")
    private Long sttcId;

    @Column(name = "sttc_name", nullable=false)
    private String sttcName;

    @Column(name = "description", nullable = false)
    private String description;

    // select,multiple select, need the lookup value
    @Column(name = "field_type")
    private String fieldType;

    // label name
    @Column(name = "field_title", nullable = false)
    private String fieldTitle;

    // field name not be space
    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Column(name = "place_holder")
    private String placeHolder;

    @Column(name = "field_width")
    private Long fieldWidth;

    @Column(name = "min_length")
    private Long minLength;

    @Column(name = "max_length")
    private Long maxLength;

    @Column(name = "field_lk_value")
    private String fieldLkValue;

    @Column(name = "mandatory")
    private Boolean mandatory;

    @Column(name = "disabled",
        columnDefinition = "boolean default false")
    private Boolean disabled;

    @Column(name = "is_default",
        columnDefinition = "boolean default false")
    private Boolean isDefault;

    @Column(name = "default_val")
    private String defaultValue;

    @Column(name = "pattern")
    private String pattern;

    @Column(name = "status", nullable = false)
    private Long status;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    @OneToMany(mappedBy="sttc")
    private List<STTCLinkSTTS> sttcLink = new ArrayList<>();

    public STTControl() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getSttcId() {
        return sttcId;
    }

    public void setSttcId(Long sttcId) {
        this.sttcId = sttcId;
    }

    public String getSttcName() {
        return sttcName;
    }

    public void setSttcName(String sttcName) {
        this.sttcName = sttcName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getFieldTitle() {
        return fieldTitle;
    }

    public void setFieldTitle(String fieldTitle) {
        this.fieldTitle = fieldTitle;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getPlaceHolder() {
        return placeHolder;
    }

    public void setPlaceHolder(String placeHolder) {
        this.placeHolder = placeHolder;
    }

    public Long getFieldWidth() {
        return fieldWidth;
    }

    public void setFieldWidth(Long fieldWidth) {
        this.fieldWidth = fieldWidth;
    }

    public Long getMinLength() {
        return minLength;
    }

    public void setMinLength(Long minLength) {
        this.minLength = minLength;
    }

    public Long getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Long maxLength) {
        this.maxLength = maxLength;
    }

    public String getFieldLkValue() {
        return fieldLkValue;
    }

    public void setFieldLkValue(String fieldLkValue) {
        this.fieldLkValue = fieldLkValue;
    }

    public Boolean getMandatory() {
        return mandatory;
    }

    public void setMandatory(Boolean mandatory) {
        this.mandatory = mandatory;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public Long getStatus() {
        return status;
    }

    public void setStatus(Long status) {
        this.status = status;
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

    public List<STTCLinkSTTS> getSttcLink() {
        return sttcLink;
    }

    public void setSttcLink(List<STTCLinkSTTS> sttcLink) {
        this.sttcLink = sttcLink;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
