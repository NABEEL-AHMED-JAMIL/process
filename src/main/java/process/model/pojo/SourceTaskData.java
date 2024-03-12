package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

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

    @ManyToOne
    @JoinColumn(name = "stt_form_id")
    private STTForm sttForm;

    @ManyToOne
    @JoinColumn(name = "stt_section_id")
    private STTSection sttSection;

    @ManyToOne
    @JoinColumn(name = "stt_control_id")
    private STTControl sttControl;

    @Column(name = "text_value")
    private String textValue;

    @Column(name = "numeric_value")
    private Integer numericValue;

    @Column(name = "date_value")
    private Date dateValue;

    @Column(name = "time_value")
    private Time timeValue;

    @Column(name = "timestamp_value")
    private Timestamp dateTimeValue;

    @Column(name = "checkbox_value")
    private Boolean checkBoxValue;

    // can be used for both single and multi
    @Column(name = "select_value")
    private Long selectValue;

    @Column(name = "rich_text_value")
    private String richTextValue;

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

    public STTForm getSttForm() {
        return sttForm;
    }

    public void setSttForm(STTForm sttForm) {
        this.sttForm = sttForm;
    }

    public STTSection getSttSection() {
        return sttSection;
    }

    public void setSttSection(STTSection sttSection) {
        this.sttSection = sttSection;
    }

    public STTControl getSttControl() {
        return sttControl;
    }

    public void setSttControl(STTControl sttControl) {
        this.sttControl = sttControl;
    }

    public String getTextValue() {
        return textValue;
    }

    public void setTextValue(String textValue) {
        this.textValue = textValue;
    }

    public Integer getNumericValue() {
        return numericValue;
    }

    public void setNumericValue(Integer numericValue) {
        this.numericValue = numericValue;
    }

    public Date getDateValue() {
        return dateValue;
    }

    public void setDateValue(Date dateValue) {
        this.dateValue = dateValue;
    }

    public Time getTimeValue() {
        return timeValue;
    }

    public void setTimeValue(Time timeValue) {
        this.timeValue = timeValue;
    }

    public Timestamp getDateTimeValue() {
        return dateTimeValue;
    }

    public void setDateTimeValue(Timestamp dateTimeValue) {
        this.dateTimeValue = dateTimeValue;
    }

    public Boolean getCheckBoxValue() {
        return checkBoxValue;
    }

    public void setCheckBoxValue(Boolean checkBoxValue) {
        this.checkBoxValue = checkBoxValue;
    }

    public Long getSelectValue() {
        return selectValue;
    }

    public void setSelectValue(Long selectValue) {
        this.selectValue = selectValue;
    }

    public String getRichTextValue() {
        return richTextValue;
    }

    public void setRichTextValue(String richTextValue) {
        this.richTextValue = richTextValue;
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
