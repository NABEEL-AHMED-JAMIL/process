package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.HighlighterStatus;
import process.model.enums.Status;
import java.sql.Timestamp;

@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PdfHighlighterTaskDto {

    private Long pdfHighlighterTaskId;
    private String taskName;
    private String description;
    private HighlighterStatus highlighterStatus;
    private Status status;
    private Timestamp dateCreated;

    public PdfHighlighterTaskDto() {
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
