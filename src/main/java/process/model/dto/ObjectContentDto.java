package process.model.dto;

import java.io.InputStream;

/**
 * Carries an object's raw byte stream alongside the response headers (content type, size,
 * file name) the controller needs to stream it back for preview or download. When the
 * request asked for a byte range, {@code size} is the length of that range while
 * {@code totalSize} is the full object's size (needed for the Content-Range header).
 * @author Nabeel Ahmed
 */
public class ObjectContentDto {

    private InputStream content;
    private String contentType;
    private long size;
    private long totalSize;
    private String fileName;

    public ObjectContentDto(InputStream content, String contentType, long size, String fileName) {
        this(content, contentType, size, size, fileName);
    }

    public ObjectContentDto(InputStream content, String contentType, long size, long totalSize, String fileName) {
        this.content = content;
        this.contentType = contentType;
        this.size = size;
        this.totalSize = totalSize;
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

    public long getTotalSize() {
        return totalSize;
    }

    public String getFileName() {
        return fileName;
    }

}
