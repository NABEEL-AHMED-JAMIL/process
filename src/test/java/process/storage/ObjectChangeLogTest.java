package process.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.storage.ObjectChanged;
import org.barco.platform.storage.StorageTopics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.outbox.OutboxWriter;
import process.security.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** What reaches the bus for media-service to act on: the platform envelope, keyed by bucket (ADR-013). */
class ObjectChangeLogTest {

    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final ObjectChangeLog log = new ObjectChangeLog(this.outbox);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aChangeIsWrittenInThePlatformEnvelopeKeyedByBucket() throws Exception {
        TenantContext.set(2905L, "TENANT_USER", 10L, "ops@medaxis.example");

        this.log.prefix("docs", "q3/", ObjectChanged.Reason.DELETE_FOLDER);

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(this.outbox).write(eq(StorageTopics.OBJECT_CHANGED), eq("docs"), anyString(), event.capture());
        JsonNode wire = new ObjectMapper().readTree(event.getValue());
        assertThat(wire.get("eventType").asText()).isEqualTo(StorageTopics.OBJECT_CHANGED);
        assertThat(wire.get("producer").asText()).isEqualTo("process");
        assertThat(wire.get("tenantId").asLong()).isEqualTo(2905L);
        assertThat(wire.get("payload").get("bucket").asText()).isEqualTo("docs");
        assertThat(wire.get("payload").get("target").asText()).isEqualTo("q3/");
        assertThat(wire.get("payload").get("scope").asText()).isEqualTo("PREFIX");
        assertThat(wire.get("payload").get("reason").asText()).isEqualTo("DELETE_FOLDER");
    }

    /** A workflow thread has no tenant; the announcement still goes -- Media's cache is keyed by bucket, not tenant. */
    @Test
    void aWorkflowWithNoTenantStillAnnounces() {
        this.log.object("etl-config", "kafka/truststore.p12", ObjectChanged.Reason.UPLOAD);

        verify(this.outbox).write(eq(StorageTopics.OBJECT_CHANGED), eq("etl-config"), anyString(), anyString());
    }
}
