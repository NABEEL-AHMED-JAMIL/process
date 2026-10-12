package process.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * One approved document, as Document Intelligence announces it (MIG-270): a PlatformEvent of type
 * {@link DocumentTopics#DOCUMENT_APPROVED} whose envelope names the workspace and whose payload names the document --
 * extractionId (what the review screen opens), ocrDocumentId, documentTypeId, typeKey, typeVersion, approval ("auto" or
 * "reviewer"), approvedBy -- and never its values (those are the type's dataset row, read under the run's own access).
 * Read strictly: an event missing any of it, or naming two workspaces, is unreadable, and nothing is started from it.
 */
public final class DocumentApproved {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String eventId;
    private final String traceId;
    private final long tenantId;
    private final long extractionId;
    private final Long ocrDocumentId;
    private final long documentTypeId;
    private final String typeKey;
    private final int typeVersion;
    private final String approval;
    private final Long approvedBy;

    DocumentApproved(String eventId, String traceId, long tenantId, long extractionId, Long ocrDocumentId, long documentTypeId,
        String typeKey, int typeVersion, String approval, Long approvedBy) {
        this.eventId = eventId;
        this.traceId = traceId;
        this.tenantId = tenantId;
        this.extractionId = extractionId;
        this.ocrDocumentId = ocrDocumentId;
        this.documentTypeId = documentTypeId;
        this.typeKey = typeKey;
        this.typeVersion = typeVersion;
        this.approval = approval;
        this.approvedBy = approvedBy;
    }

    /** Reads ai-service's event; IllegalArgumentException, saying why, when it is not a readable approval. */
    public static DocumentApproved parse(String message) {
        JsonNode event;
        try {
            event = JSON.readTree(message);
        } catch (IOException | RuntimeException unreadable) {
            throw new IllegalArgumentException("not JSON");
        }
        if (event == null || !DocumentTopics.DOCUMENT_APPROVED.equals(text(event, "eventType"))) {
            throw new IllegalArgumentException("not a " + DocumentTopics.DOCUMENT_APPROVED + " event");
        }
        JsonNode payload = event.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("the event has no payload");
        }
        JsonNode tenant = event.get("tenantId");
        if (tenant == null || !tenant.isIntegralNumber() || tenant.asLong() <= 0) {
            throw new IllegalArgumentException("the event names no workspace");
        }
        JsonNode payloadTenant = payload.get("tenantId");
        if (payloadTenant != null && !payloadTenant.isNull() && payloadTenant.asLong() != tenant.asLong()) {
            throw new IllegalArgumentException("the event names two workspaces");
        }
        Long extraction = positive(payload, "extractionId");
        Long type = positive(payload, "documentTypeId");
        Long version = positive(payload, "typeVersion");
        if (extraction == null || type == null || version == null || version > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("the event does not say which document of which type was approved");
        }
        return new DocumentApproved(text(event, "eventId"), text(event, "traceId"), tenant.asLong(), extraction, positive(payload, "ocrDocumentId"),
            type, text(payload, "typeKey"), version.intValue(), text(payload, "approval"), positive(payload, "approvedBy"));
    }

    private static Long positive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isIntegralNumber() || value.asLong() <= 0 ? null : value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    public String getEventId() { return this.eventId; }

    public String getTraceId() { return this.traceId; }

    public long getTenantId() { return this.tenantId; }

    public long getExtractionId() { return this.extractionId; }

    public Long getOcrDocumentId() { return this.ocrDocumentId; }

    public long getDocumentTypeId() { return this.documentTypeId; }

    public String getTypeKey() { return this.typeKey; }

    public int getTypeVersion() { return this.typeVersion; }

    public String getApproval() { return this.approval; }

    public Long getApprovedBy() { return this.approvedBy; }

    @Override
    public String toString() {
        return String.format("document %d (type %d v%d, %s)", this.extractionId, this.documentTypeId, this.typeVersion, this.approval);
    }
}
