package process.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * One file's arrival in a workspace's inbox, as Storage announces it (MIG-239): a PlatformEvent of type
 * {@link InboxTopics#INBOX_ARRIVED} whose payload names the arrival, its workspace, the inbox connection's alias, the key
 * under intake/, the file's name, size and type. Read strictly -- an event missing any of it, or naming two workspaces,
 * is unreadable, and nothing is started from it.
 */
public final class InboxArrival {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final String eventId;
    private final String traceId;
    private final String arrivalId;
    private final long tenantId;
    private final String alias;
    private final String key;
    private final String fileName;
    private final long bytes;
    private final String contentType;

    InboxArrival(String eventId, String traceId, String arrivalId, long tenantId, String alias, String key, String fileName, long bytes,
        String contentType) {
        this.eventId = eventId;
        this.traceId = traceId;
        this.arrivalId = arrivalId;
        this.tenantId = tenantId;
        this.alias = alias;
        this.key = key;
        this.fileName = fileName;
        this.bytes = bytes;
        this.contentType = contentType;
    }

    /** Reads Storage's event; IllegalArgumentException, saying why, when it is not a readable arrival. */
    public static InboxArrival parse(String message) {
        JsonNode event;
        try {
            event = JSON.readTree(message);
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalArgumentException("not JSON");
        }
        if (event == null || !InboxTopics.INBOX_ARRIVED.equals(text(event, "eventType"))) {
            throw new IllegalArgumentException("not an " + InboxTopics.INBOX_ARRIVED + " event");
        }
        JsonNode payload = event.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("the event has no payload");
        }
        String arrivalId = text(payload, "arrivalId");
        if (arrivalId == null || !UUID.matcher(arrivalId).matches()) {
            throw new IllegalArgumentException("the event names no arrival id");
        }
        JsonNode tenant = payload.get("tenantId");
        if (tenant == null || !tenant.isIntegralNumber() || tenant.asLong() <= 0) {
            throw new IllegalArgumentException("the event names no workspace");
        }
        JsonNode envelopeTenant = event.get("tenantId");
        if (envelopeTenant != null && !envelopeTenant.isNull() && envelopeTenant.asLong() != tenant.asLong()) {
            throw new IllegalArgumentException("the event names two workspaces");
        }
        String alias = text(payload, "alias");
        String key = text(payload, "key");
        String fileName = text(payload, "fileName");
        JsonNode bytes = payload.get("bytes");
        if (alias == null || alias.trim().isEmpty() || key == null || !key.startsWith("intake/") || fileName == null
            || fileName.trim().isEmpty() || bytes == null || !bytes.isIntegralNumber() || bytes.asLong() < 0) {
            throw new IllegalArgumentException("the event does not say which file arrived where");
        }
        return new InboxArrival(text(event, "eventId"), text(event, "traceId"), arrivalId, tenant.asLong(), alias, key, fileName,
            bytes.asLong(), text(payload, "contentType"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    public String getEventId() { return this.eventId; }
    public String getTraceId() { return this.traceId; }
    public String getArrivalId() { return this.arrivalId; }
    public long getTenantId() { return this.tenantId; }
    public String getAlias() { return this.alias; }
    public String getKey() { return this.key; }
    public String getFileName() { return this.fileName; }
    public long getBytes() { return this.bytes; }
    public String getContentType() { return this.contentType; }
}
