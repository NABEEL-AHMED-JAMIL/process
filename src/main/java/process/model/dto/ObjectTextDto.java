package process.model.dto;

/**
 * An object in a bucket, as text a prompt can read: what the file chat extracts, handed to
 * whoever wants to put a file into a variable.
 */
public class ObjectTextDto {

    private String bucket;
    private String key;
    private String name;
    private String etag;
    private Long size;
    private String contentType;
    /** text | transcript | description -- how the words were obtained. */
    private String kind;
    private String text;
    private int chars;
    private int totalChars;
    private boolean truncated;

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getChars() { return chars; }
    public void setChars(int chars) { this.chars = chars; }
    public int getTotalChars() { return totalChars; }
    public void setTotalChars(int totalChars) { this.totalChars = totalChars; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
