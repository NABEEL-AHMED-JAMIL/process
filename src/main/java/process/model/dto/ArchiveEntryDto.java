package process.model.dto;

/** One entry of an archive, as the object browser lists it without downloading the archive. */
public class ArchiveEntryDto {

    private String name;
    private boolean directory;
    private long size;
    private long compressedSize;
    private long lastModified;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isDirectory() { return directory; }
    public void setDirectory(boolean directory) { this.directory = directory; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public long getCompressedSize() { return compressedSize; }
    public void setCompressedSize(long compressedSize) { this.compressedSize = compressedSize; }
    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
}
