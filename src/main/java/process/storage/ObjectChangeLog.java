package process.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.event.PlatformEvent;
import org.barco.platform.storage.ObjectChanged;
import org.barco.platform.storage.StorageTopics;
import org.springframework.stereotype.Component;
import process.outbox.OutboxWriter;
import process.security.TenantContext;

/**
 * Storage's announcements that an object or a folder changed (ADR-013), written to the outbox that
 * already carries process's events to the bus. The relay delivers them at least once, in order per
 * bucket; Media drops the text it extracted from what changed.
 *
 * Throws when the outbox cannot take the announcement. The caller records BEFORE it writes, so that
 * failure refuses the write instead of leaving derived text behind a write nobody announced.
 */
@Component
public class ObjectChangeLog {

    static final String PRODUCER = "process";

    private final OutboxWriter outbox;
    private final ObjectMapper json = new ObjectMapper();

    public ObjectChangeLog(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void object(String bucket, String key, ObjectChanged.Reason reason) {
        this.record(ObjectChanged.object(bucket, key, reason));
    }

    public void prefix(String bucket, String prefix, ObjectChanged.Reason reason) {
        this.record(ObjectChanged.prefix(bucket, prefix, reason));
    }

    private void record(ObjectChanged change) {
        PlatformEvent<ObjectChanged> event = PlatformEvent.of(StorageTopics.OBJECT_CHANGED, TenantContext.getTenantId(),
            PRODUCER, change.validated());
        try {
            this.outbox.write(StorageTopics.OBJECT_CHANGED, change.getBucket(), event.getEventId(), this.json.writeValueAsString(event));
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("Could not serialise an ObjectChanged for " + change.getBucket(), unwritable);
        }
    }
}
