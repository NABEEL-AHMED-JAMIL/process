package process.pipeline.items;

import java.util.Map;

/**
 * What a per-row step said about one row's item during a try (MIG-420): it failed, it took so long, it made a file; MIG-421:
 * it reused what an earlier attempt made for it (a report: not made or billed again), or it flagged it (an OCR page to
 * check by hand).
 */
public final class ItemReport {

    public enum Kind { FAILED, TOOK, FILE, REUSED, FLAGGED }

    public final Kind kind;
    public final Map<String, Object> row;
    public final String reason;
    public final long millis;
    public final String fileName;
    public final String bucket;
    public final String objectKey;
    public final long bytes;
    /** REUSED: the attempt that made what was reused. FLAGGED: the flag (ItemRow.CHECK). */
    public final int fromAttempt;
    public final String flag;

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
        this.fromAttempt = 0;
        this.flag = null;
    }

    private ItemReport(Kind kind, Map<String, Object> row, String reason, int fromAttempt, String flag) {
        this.kind = kind;
        this.row = row;
        this.reason = reason;
        this.millis = 0;
        this.fileName = null;
        this.bucket = null;
        this.objectKey = null;
        this.bytes = 0;
        this.fromAttempt = fromAttempt;
        this.flag = flag;
    }

    /** MIG-421: the step reused what attempt {@code fromAttempt} made for the row's item ({@code note} says what). */
    public static ItemReport reused(Map<String, Object> row, String note, int fromAttempt) {
        return new ItemReport(Kind.REUSED, row, note, fromAttempt, null);
    }

    /** MIG-421: the step flagged the row's item ({@code flag}: ItemRow.CHECK), {@code note} saying why. */
    public static ItemReport flagged(Map<String, Object> row, String flag, String note) {
        return new ItemReport(Kind.FLAGGED, row, note, 0, flag);
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
