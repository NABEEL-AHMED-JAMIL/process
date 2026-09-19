package process.billing;

/**
 * The meters the console reports to the metering service: the key the ledger stores and the
 * unit the quantity is counted in. The rate card prices these same keys; a name written here
 * and a name seeded in the meter's card must agree, which is why there is one list.
 *
 * Byte meters carry bytes (priced per GB by the card); the one storage measure in GB-hours is
 * built with {@link UsageEvent#gb(long)}.
 */
public enum Meter {

    STORAGE_GB_HOURS("storage.gb_hours", "GB-hour"),
    STORAGE_BYTES_WRITTEN("storage.bytes.written", "byte"),
    STORAGE_BYTES_READ("storage.bytes.read", "byte"),
    STORAGE_BYTES_DELETED("storage.bytes.deleted", "byte"),
    STORAGE_OPS_READ("storage.ops.read", "op"),
    STORAGE_OPS_WRITE("storage.ops.write", "op"),
    STORAGE_OPS_DELETE("storage.ops.delete", "op"),
    PIPELINE_RUNS("pipeline.runs", "run"),
    PIPELINE_WORKER_MINUTES("pipeline.worker_minutes", "minute"),
    AI_TOKENS_IN("ai.tokens.in", "token"),
    AI_TOKENS_OUT("ai.tokens.out", "token"),
    AI_VISION_IMAGES("ai.vision.images", "image"),
    AI_TRANSCRIPT_MINUTES("ai.transcript.minutes", "minute"),
    ANALYTICS_QUERIES("analytics.queries", "query"),
    ANALYTICS_GB_SCANNED("analytics.gb_scanned", "GB"),
    CONVERT_DOCUMENTS("convert.documents", "document"),
    KAFKA_TOPIC_HOURS("kafka.topic_hours", "topic-hour"),
    SEATS_USER_DAYS("seats.user_days", "user-day");

    private final String key;
    private final String unit;

    Meter(String key, String unit) { this.key = key; this.unit = unit; }

    public String key() { return this.key; }

    public String unit() { return this.unit; }
}
