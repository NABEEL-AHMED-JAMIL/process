package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectSummaryDto {

    private String name;
    private String key;
    private boolean folder;
    private Long size;
    private String lastModified;
    private String etag;
    private String contentType;

    public ObjectSummaryDto() {}

    public ObjectSummaryDto(String name, String key, boolean folder, Long size, String lastModified) {
        this(name, key, folder, size, lastModified, null, null);
    }

    public ObjectSummaryDto(String name, String key, boolean folder, Long size, String lastModified,
            String etag, String contentType) {
        this.name = name;
        this.key = key;
        this.folder = folder;
        this.size = size;
        this.lastModified = lastModified;
        this.etag = etag;
        this.contentType = contentType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public boolean isFolder() {
        return folder;
    }

    public void setFolder(boolean folder) {
        this.folder = folder;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
