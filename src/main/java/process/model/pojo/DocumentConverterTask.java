package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "document_converter_task", indexes = {
    @Index(name = "idx_document_converter_task_tenant_id", columnList = "tenant_id")
})
/**
 * @author Nabeel Ahmed
 * */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentConverterTask {

    @GenericGenerator(
        name = "documentConverterTaskSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "document_converter_task_id_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "document_converter_task_id", unique = true, nullable = false)
    @GeneratedValue(generator = "documentConverterTaskSequenceGenerator")
    private Long documentConverterTaskId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Column(name = "input_file_name", nullable = false)
    private String inputFileName;

    @Column(name = "input_format", nullable = false)
    private String inputFormat;

    @Column(name = "input_content_type")
    private String inputContentType;

    @Column(name = "input_file_size")
    private Long inputFileSize;

    @Column(name = "output_format", nullable = false)
    private String outputFormat;

    @Column(name = "output_file_name")
    private String outputFileName;

    @Column(name = "output_content_type")
    private String outputContentType;

    @Column(name = "output_file_size")
    private Long outputFileSize;

    @Column(name = "bucket_name", nullable = false)
    private String bucketName;

    @Column(name = "target_folder")
    private String targetFolder;

    @Column(name = "input_storage_key", nullable = false)
    private String inputStorageKey;

    @Column(name = "output_storage_key", nullable = false)
    private String outputStorageKey;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    public DocumentConverterTask() {
    }

    @PrePersist
    protected void onCreate() {
        this.dateCreated = new Timestamp(System.currentTimeMillis());
        if (this.status == null) {
            this.status = Status.Active;
        }
    }

    public Long getDocumentConverterTaskId() {
        return documentConverterTaskId;
    }

    public void setDocumentConverterTaskId(Long documentConverterTaskId) {
        this.documentConverterTaskId = documentConverterTaskId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Tenant getTenant() {
        return tenant;
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

    public String getTargetFolder() {
        return targetFolder;
    }

    public void setTargetFolder(String targetFolder) {
        this.targetFolder = targetFolder;
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

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
