package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Set;

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
    private Long sttCId;

    @Column(name = "sttc_order")
    private Long sttCOrder;

    @Column(name = "control_name", nullable=false)
    private String controlName;

    @Column(name = "filed_title", nullable = false)
    private String filedTitle;

    @Column(name = "filed_name", nullable = false)
    private String filedName;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "place_holder")
    private String placeHolder;

    // select,multiple select, need the lookup value
    @Column(name = "filed_type")
    private String filedType;

    @Column(name = "filed_look_up")
    private Long filedLookUp;

    @Column(name = "filed_width")
    private Long filedWidth;

    @Column(name = "separate_line")
    private boolean separateLine;

    @Column(name = "min_length")
    private Long minLength;

    @Column(name = "max_length")
    private Long maxLength;

    @Column(name = "mandatory")
    private boolean mandatory;

    @Column(name = "is_default",
        columnDefinition = "boolean default false")
    private Boolean isDefault;

    @Column(name = "pattern")
    private String pattern;

    @Column(name = "sttc_status",nullable = false)
    @Enumerated(EnumType.ORDINAL)
    private Status status;

    @ManyToOne
    @JoinColumn(name="app_user_id")
    private AppUser appUser;

    @Column(name = "filed_new_line")
    private boolean filedNewLine;

    @Column(name = "date_created",
            nullable = false)
    private Timestamp dateCreated;

    @ManyToMany(mappedBy = "appUserSTTControls")
    private Set<STTSection> sttSections = new LinkedHashSet<>();

    public STTControl() {
    }

    public Long getSttCId() {
        return sttCId;
    }

    public void setSttCId(Long sttCId) {
        this.sttCId = sttCId;
    }

    public Long getSttCOrder() {
        return sttCOrder;
    }

    public void setSttCOrder(Long sttCOrder) {
        this.sttCOrder = sttCOrder;
    }

    public String getControlName() {
        return controlName;
    }

    public void setControlName(String controlName) {
        this.controlName = controlName;
    }

    public String getFiledTitle() {
        return filedTitle;
    }

    public void setFiledTitle(String filedTitle) {
        this.filedTitle = filedTitle;
    }

    public String getFiledName() {
        return filedName;
    }

    public void setFiledName(String filedName) {
        this.filedName = filedName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPlaceHolder() {
        return placeHolder;
    }

    public void setPlaceHolder(String placeHolder) {
        this.placeHolder = placeHolder;
    }

    public String getFiledType() {
        return filedType;
    }

    public void setFiledType(String filedType) {
        this.filedType = filedType;
    }

    public Long getFiledLookUp() {
        return filedLookUp;
    }

    public void setFiledLookUp(Long filedLookUp) {
        this.filedLookUp = filedLookUp;
    }

    public Long getFiledWidth() {
        return filedWidth;
    }

    public void setFiledWidth(Long filedWidth) {
        this.filedWidth = filedWidth;
    }

    public boolean isSeparateLine() {
        return separateLine;
    }

    public void setSeparateLine(boolean separateLine) {
        this.separateLine = separateLine;
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

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public void setAppUser(AppUser appUser) {
        this.appUser = appUser;
    }

    public boolean isFiledNewLine() {
        return filedNewLine;
    }

    public void setFiledNewLine(boolean filedNewLine) {
        this.filedNewLine = filedNewLine;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public Set<STTSection> getSttSections() {
        return sttSections;
    }

    public void setSttSections(Set<STTSection> sttSections) {
        this.sttSections = sttSections;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
