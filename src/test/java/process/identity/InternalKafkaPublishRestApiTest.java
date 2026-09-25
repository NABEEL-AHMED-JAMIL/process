package process.identity;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.config.KafkaConnectionResolver;
import process.config.KafkaRouteUnresolvedException;
import process.config.KafkaTemplateProvider;
import process.model.pojo.KafkaConnectionProfile;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-015 / MIG-176: Core publishes Analytics' query event through the workspace's own Kafka
 * profile, so the profile's credentials never leave Core. Only the allow-listed topic is accepted,
 * and the tenant is taken from the request -- the publisher has no principal to read it from.
 */
class InternalKafkaPublishRestApiTest {

    private static final String TOKEN = "t0ken";
    private final KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);
    private final KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
    private final InternalKafkaPublishRestApi api = new InternalKafkaPublishRestApi(this.resolver, this.templates, TOKEN);

    private static Map<String, Object> event(String topic) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", 2905);
        body.put("topic", topic);
        body.put("key", "2905");
        body.put("payload", "{\"event\":\"analytics.query.completed\"}");
        return body;
    }

    @Test
    void withoutTheServiceTokenNothingIsPublished() {
        assertThat(this.api.publish(null, event("analytics.query.completed")).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.resolver, this.templates);
    }

    @Test
    void aTopicThatIsNotOnTheListIsRefused() {
        assertThat(this.api.publish(TOKEN, event("job.events")).getStatusCodeValue()).isEqualTo(400);
        verifyNoInteractions(this.resolver, this.templates);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEventGoesOutThroughTheWorkspacesOwnProfileAtTierThree() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        KafkaConnectionProfile own = new KafkaConnectionProfile();
        when(this.resolver.require(2905L, null)).thenReturn(own);
        when(this.templates.getTemplate(own)).thenReturn(template);
        when(template.send(any(ProducerRecord.class))).thenReturn(new SettableListenableFuture<>());

        assertThat(this.api.publish(TOKEN, event("analytics.query.completed")).getStatusCodeValue()).isEqualTo(202);

        // No task type: the resolver enters at the workspace's default (the platform's, for a workspace with none of its own).
        verify(this.resolver).require(eq(2905L), isNull());
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("analytics.query.completed");
        assertThat(sent.getValue().key()).isEqualTo("2905");
        assertThat(sent.getValue().value()).isEqualTo("{\"event\":\"analytics.query.completed\"}");
    }

    /**
     * MIG-45: a workspace that resolves to no Kafka connection is answered 422 with the reason, and the event
     * is published nowhere -- not on the application's own brokers.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUnresolvedWorkspaceIsRefusedAndNothingIsPublished() {
        when(this.resolver.require(2905L, null)).thenThrow(
            new KafkaRouteUnresolvedException("No Kafka connection is set for this workspace: set a default connection."));

        ResponseEntity<?> answer = this.api.publish(TOKEN, event("analytics.query.completed"));

        assertThat(answer.getStatusCodeValue()).isEqualTo(422);
        assertThat(answer.getBody()).isEqualTo(Collections.singletonMap("message",
            "No Kafka connection is set for this workspace: set a default connection."));
        verifyNoInteractions(this.templates);
    }
}
