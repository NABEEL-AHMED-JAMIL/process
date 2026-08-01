package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.HighlighterStatus;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * This PdfHighlighterTask holds the basic detail of a PDF highlighter task
 * (name + highlighter status + status) -- no organization/form linkage, that
 * lives on the io-frontend side of this feature.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "pdf_highlighter_task")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PdfHighlighterTask {

    @GenericGenerator(
        name = "pdfHighlighterTaskSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "pdf_highlighter_task_id_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "pdf_highlighter_task_id", unique = true, nullable = false)
    @GeneratedValue(generator = "pdfHighlighterTaskSequenceGenerator")
    private Long pdfHighlighterTaskId;

    @Column(name = "task_name",
        nullable = false)
    private String taskName;

    @Column(name = "description")
    private String description;

    @Column(name = "highlighter_status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private HighlighterStatus highlighterStatus;

    // status of task (active or disable or delete)
    @Column(name = "status",
        nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "date_created",
        nullable = false)
    private Timestamp dateCreated;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_content_type")
    private String fileContentType;

    public PdfHighlighterTask() { }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Long getPdfHighlighterTaskId() {
        return pdfHighlighterTaskId;
    }

    public void setPdfHighlighterTaskId(Long pdfHighlighterTaskId) {
        this.pdfHighlighterTaskId = pdfHighlighterTaskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public HighlighterStatus getHighlighterStatus() {
        return highlighterStatus;
    }

    public void setHighlighterStatus(HighlighterStatus highlighterStatus) {
        this.highlighterStatus = highlighterStatus;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileContentType() {
        return fileContentType;
    }

    public void setFileContentType(String fileContentType) {
        this.fileContentType = fileContentType;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
