package process.pipeline.items;

import java.util.Map;

/** What a per-row step said about one row's item during a try (MIG-420): it failed, it took so long, it made a file. */
public final class ItemReport {

    public enum Kind { FAILED, TOOK, FILE }

    public final Kind kind;
    public final Map<String, Object> row;
    public final String reason;
    public final long millis;
    public final String fileName;
    public final String bucket;
    public final String objectKey;
    public final long bytes;

    private ItemReport(Kind kind, Map<String, Object> row, String reason, long millis, String fileName, String bucket, String objectKey,
        long bytes) {
        this.kind = kind;
        this.row = row;
        this.reason = reason;
        this.millis = millis;
        this.fileName = fileName;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.bytes = bytes;
    }

    public static ItemReport failed(Map<String, Object> row, String reason) {
        return new ItemReport(Kind.FAILED, row, reason, 0, null, null, null, 0);
    }

    public static ItemReport took(Map<String, Object> row, long millis) {
        return new ItemReport(Kind.TOOK, row, null, millis, null, null, null, 0);
    }

    public static ItemReport file(Map<String, Object> row, String fileName, String bucket, String objectKey, long bytes) {
        return new ItemReport(Kind.FILE, row, null, 0, fileName, bucket, objectKey, bytes);
    }
}
