package process.identity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.directory.IdentityTopics;
import process.outbox.OutboxWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-166: POST /internal/identity/events, where identity-service's relay hands over its lifecycle events.
 * They go into platform_outbox in the order given, all or none, for the outbox relay to publish on the two
 * identity topics; an event already taken is not written twice (the relay re-sends after a crash); anything
 * but the two topics, or a batch without the service token, writes nothing.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class InternalIdentityEventsRestApiPostgresTest {

    private static final String TOKEN = "t0ken";
    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private InternalIdentityEventsRestApi api;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("mig166_intake");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        this.sql = db.jdbc();
        this.sql.update("TRUNCATE platform_outbox RESTART IDENTITY");
        this.api = new InternalIdentityEventsRestApi(new OutboxWriter(this.sql), db.transactions(), TOKEN);
    }

    private static Map<String, Object> event(String id, String topic, String key, String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id);
        event.put("topic", topic);
        event.put("key", key);
        event.put("event", "{\"eventId\": \"" + id + "\", \"eventType\": \"" + type + "\", \"payload\": {}}");
        return event;
    }

    private static Map<String, Object> batch(Map<String, Object>... events) {
        return Collections.singletonMap("events", new ArrayList<>(Arrays.asList(events)));
    }

    private List<String> outbox() {
        return this.sql.queryForList("SELECT event_id || ' ' || topic || ' ' || message_key FROM platform_outbox ORDER BY outbox_id",
            String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBatchGoesIntoTheOutboxInOrderAndOnlyOnce() {
        Map<String, Object> body = batch(
            event("e-1", IdentityTopics.TENANT, "2901", "tenant.created"),
            event("e-2", IdentityTopics.USER, "7001", "user.created"),
            event("e-3", IdentityTopics.TENANT, "2901", "tenant.deleted"));

        ResponseEntity<?> first = this.api.events(TOKEN, body);
        assertThat(first.getStatusCodeValue()).isEqualTo(200);
        assertThat((Map<String, Object>) first.getBody()).containsEntry("accepted", 3).containsEntry("duplicates", 0);
        assertThat(outbox()).containsExactly("e-1 platform.identity.tenant.v1 2901", "e-2 platform.identity.user.v1 7001",
            "e-3 platform.identity.tenant.v1 2901");

        ResponseEntity<?> again = this.api.events(TOKEN, body);
        assertThat(again.getStatusCodeValue()).isEqualTo(200);
        assertThat((Map<String, Object>) again.getBody()).containsEntry("accepted", 0).containsEntry("duplicates", 3);
        assertThat(outbox()).hasSize(3);
    }

    @Test
    void withoutTheServiceTokenNothingIsWritten() {
        Map<String, Object> body = batch(event("e-1", IdentityTopics.USER, "7001", "user.created"));
        assertThat(this.api.events(null, body).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.events("wrong", body).getStatusCodeValue()).isEqualTo(401);
        assertThat(outbox()).isEmpty();
    }

    @Test
    void anythingButTheTwoIdentityTopicsRefusesTheWholeBatch() {
        assertThat(this.api.events(TOKEN, batch(
            event("e-1", IdentityTopics.USER, "7001", "user.created"),
            event("e-2", "platform.notification.job-status.v1", "1", "user.created"))).getStatusCodeValue()).isEqualTo(400);
        assertThat(this.api.events(TOKEN, batch(event("e-3", IdentityTopics.USER, "7001", "tenant.deleted"))).getStatusCodeValue())
            .as("a workspace event on the people topic").isEqualTo(400);
        assertThat(this.api.events(TOKEN, batch(event("e-4", IdentityTopics.USER, "", "user.created"))).getStatusCodeValue())
            .isEqualTo(400);
        assertThat(this.api.events(TOKEN, batch(event(null, IdentityTopics.USER, "7", "user.created"))).getStatusCodeValue())
            .isEqualTo(400);
        assertThat(this.api.events(TOKEN, Collections.singletonMap("events", "no")).getStatusCodeValue()).isEqualTo(400);
        assertThat(outbox()).isEmpty();
    }
}
