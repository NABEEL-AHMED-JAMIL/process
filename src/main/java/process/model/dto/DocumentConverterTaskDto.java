package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.sql.Timestamp;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentConverterTaskDto {

    private Long documentConverterTaskId;
    private String taskName;
    private String inputFileName;
    private String inputFormat;
    private String inputContentType;
    private Long inputFileSize;

    private String outputFormat;
    private String outputFileName;
    private String outputContentType;
    private Long outputFileSize;
    private String bucketName;
    private String inputStorageKey;
    private String outputStorageKey;
    private Status status;
    private Timestamp dateCreated;

    private String outputBase64;

    private Boolean save;

    public DocumentConverterTaskDto() {
    }

    public Long getDocumentConverterTaskId() {
        return documentConverterTaskId;
    }

    public void setDocumentConverterTaskId(Long documentConverterTaskId) {
        this.documentConverterTaskId = documentConverterTaskId;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getInputFileName() {
        return inputFileName;
    }

    public void setInputFileName(String inputFileName) {
        this.inputFileName = inputFileName;
    }

    public String getInputFormat() {
        return inputFormat;
    }

    public void setInputFormat(String inputFormat) {
        this.inputFormat = inputFormat;
    }

    public String getInputContentType() {
        return inputContentType;
    }

    public void setInputContentType(String inputContentType) {
        this.inputContentType = inputContentType;
    }

    public Long getInputFileSize() {
        return inputFileSize;
    }

    public void setInputFileSize(Long inputFileSize) {
        this.inputFileSize = inputFileSize;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public String getOutputFileName() {
        return outputFileName;
    }

    public void setOutputFileName(String outputFileName) {
        this.outputFileName = outputFileName;
    }

    public String getOutputContentType() {
        return outputContentType;
    }

    public void setOutputContentType(String outputContentType) {
        this.outputContentType = outputContentType;
    }

    public Long getOutputFileSize() {
        return outputFileSize;
    }

    public void setOutputFileSize(Long outputFileSize) {
        this.outputFileSize = outputFileSize;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getInputStorageKey() {
        return inputStorageKey;
    }

    public void setInputStorageKey(String inputStorageKey) {
        this.inputStorageKey = inputStorageKey;
    }

    public String getOutputStorageKey() {
        return outputStorageKey;
    }

    public void setOutputStorageKey(String outputStorageKey) {
        this.outputStorageKey = outputStorageKey;
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

    public String getOutputBase64() {
        return outputBase64;
    }

    public void setOutputBase64(String outputBase64) {
        this.outputBase64 = outputBase64;
    }

    public Boolean getSave() {
        return save;
    }

    public void setSave(Boolean save) {
        this.save = save;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
