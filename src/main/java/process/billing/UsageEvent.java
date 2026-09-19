package process.billing;

import java.time.Instant;

/**
 * One line for the meter: what was used, how much, by whom, on behalf of which workspace.
 *
 * Built where the usage happens and handed to {@link MeterClient}; the client adds nothing
 * but the transport. `dedupeKey` is the caller's promise that this exact event is reported
 * once -- a retry, a replay or a restart with the same key is a duplicate at the meter.
 */
public class UsageEvent {

    public Long tenantId;
    public String meter;
    public double quantity;
    public String unit;
    public Instant occurredAt = Instant.now();
    public String source = "console";
    public String subjectType;
    public String subjectId;
    public Long actorUserId;
    public Long jobQueueId;
    public String dedupeKey;
    public String note;

    public static UsageEvent of(Long tenantId, String meter, double quantity, String unit, String dedupeKey) {
        UsageEvent e = new UsageEvent();
        e.tenantId = tenantId; e.meter = meter; e.quantity = quantity; e.unit = unit; e.dedupeKey = dedupeKey;
        return e;
    }

    public UsageEvent subject(String type, String id) { this.subjectType = type; this.subjectId = id; return this; }
    public UsageEvent actor(Long appUserId) { this.actorUserId = appUserId; return this; }
    public UsageEvent run(Long jobQueueId) { this.jobQueueId = jobQueueId; return this; }
    public UsageEvent source(String source) { this.source = source; return this; }
    public UsageEvent note(String note) { this.note = note; return this; }
    public UsageEvent at(Instant when) { this.occurredAt = when; return this; }

    /** Bytes as the GB the storage meters are priced in. */
    public static double gb(long bytes) { return bytes / (1024.0 * 1024 * 1024); }
}
