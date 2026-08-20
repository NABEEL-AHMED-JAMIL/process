package process.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRawValue;
import org.springframework.web.multipart.MultipartFile;
import com.google.gson.Gson;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "file", "files", "data" })
public class FileUploadDto<T> {

    @JsonProperty("file")
    private MultipartFile file;

    @JsonProperty("files")
    private List<MultipartFile> files;

    @JsonRawValue
    @JsonProperty("data")
    private T data;

    @JsonProperty("tenantId")
    private Long tenantId;

    public FileUploadDto() { }

    public FileUploadDto(T data) { this.data = data; }

    public FileUploadDto(MultipartFile file, T data) {
        this.file = file;
        this.data = data;
    }

    public FileUploadDto(List<MultipartFile> files, T data) {
        this.files = files;
        this.data = data;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }

    public List<MultipartFile> getFiles() {
        return files;
    }

    public void setFiles(List<MultipartFile> files) {
        this.files = files;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}