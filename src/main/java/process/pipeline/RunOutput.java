package process.pipeline;

/**
 * One file a step wrote, for the run's result manifest (run_output, V188; Wave 4): Save File's kept file, or Upload to
 * bucket's object. A step reports it through {@link StepContext#recordOutput}; the engine records it once per step per
 * attempt (a later try replaces an earlier one's).
 */
public final class RunOutput {

    public static final String FILE = "file";
    public static final String BUCKET = "bucket";

    private final String kind;
    private final String name;
    private final String format;
    private final long rows;
    private final long bytes;
    private final String bucket;
    private final String key;

    private RunOutput(String kind, String name, String format, long rows, long bytes, String bucket, String key) {
        this.kind = kind;
        this.name = name;
        this.format = format;
        this.rows = rows;
        this.bytes = bytes;
        this.bucket = bucket;
        this.key = key;
    }

    /** A file kept with the run (StepContext.keepFile) under this name. */
    public static RunOutput file(String fileName, String format, long rows, long bytes) {
        return new RunOutput(FILE, fileName, format, rows, bytes, null, null);
    }

    /** An object written to a workspace bucket: its alias and key, which storage-service's browse endpoints read. */
    public static RunOutput bucket(String bucketAlias, String objectKey, String format, long rows, long bytes) {
        int slash = objectKey.lastIndexOf('/');
        String name = slash < 0 ? objectKey : objectKey.substring(slash + 1);
        return new RunOutput(BUCKET, name.length() > 255 ? name.substring(name.length() - 255) : name, format, rows, bytes,
            bucketAlias, objectKey);
    }

    public String getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getFormat() {
        return format;
    }

    public long getRows() {
        return rows;
    }

    public long getBytes() {
        return bytes;
    }

    public String getBucket() {
        return bucket;
    }

    public String getKey() {
        return key;
    }
}
