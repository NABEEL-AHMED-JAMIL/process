package process.model.dto;

import java.io.InputStream;

/**
 * Carries an object's raw byte stream alongside the response headers (content type, size,
 * file name) the controller needs to stream it back for preview or download.
 * @author Nabeel Ahmed
 */
public class ObjectContentDto {

    private InputStream content;
    private String contentType;
    private long size;
    private String fileName;

    public ObjectContentDto(InputStream content, String contentType, long size, String fileName) {
        this.content = content;
        this.contentType = contentType;
        this.size = size;
        this.fileName = fileName;
    }

    public InputStream getContent() {
        return content;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public String getFileName() {
        return fileName;
    }

}
