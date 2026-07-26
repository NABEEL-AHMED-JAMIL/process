package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * DTO for a single object's metadata (side-panel "Object Info")
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectMetadataDto {

    private String name;
    private String key;
    private Long size;
    private String lastModified;
    private String etag;
    private String contentType;
    private boolean previewable;

    public ObjectMetadataDto() {}

    public ObjectMetadataDto(String name, String key, Long size, String lastModified,
        String etag, String contentType, boolean previewable) {
        this.name = name;
        this.key = key;
        this.size = size;
        this.lastModified = lastModified;
        this.etag = etag;
        this.contentType = contentType;
        this.previewable = previewable;
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

    public boolean isPreviewable() {
        return previewable;
    }

    public void setPreviewable(boolean previewable) {
        this.previewable = previewable;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
