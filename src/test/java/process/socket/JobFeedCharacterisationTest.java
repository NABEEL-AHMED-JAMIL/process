package process.socket;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.security.StompAuthChannelInterceptor;
import process.util.JwtUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part of the Notifications characterisation (MIG-19, MIG-162): the surface as the monolith serves
 * it today, pinned before the service is carved out. The console is not being rewritten, so each
 * case is a frozen contract the extracted service must also pass -- on the simple broker now and
 * on the broker relay that replaces it. A case marked DEFECT records current, wrong behaviour so
 * that its fix shows up as a deliberate change to this file rather than as drift.
 */
class JobFeedCharacterisationTest {

    private static final long TENANT_A = 2901L;


    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentTo(String destination) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(this.messaging).convertAndSend(org.mockito.ArgumentMatchers.eq(destination), body.capture());
        return new Gson().fromJson((String) body.getValue(), Map.class);
    }

    /** DEFECT, pinned as-is: one event, two sends -- the tenant topic and the all-tenants topic. */
    @Test
    void aStatusGoesToTheTenantsTopicAndAgainToTheAllTenantsTopic() {
        new JobEventPublisher(this.messaging).publishStatus(TENANT_A, 41L, 7001L, "Running", "Started.");

        verify(this.messaging, times(2)).convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
        Map<String, Object> event = sentTo("/topic/jobs." + TENANT_A);
        assertThat(sentTo("/topic/jobs.all")).isEqualTo(event);
        assertThat(event).containsEntry("type", "job.status").containsEntry("jobRunningStatus", "Running")
            .containsEntry("message", "Started.");
        assertThat(((Number) event.get("tenantId")).longValue()).isEqualTo(TENANT_A);
    }

    /** The console parses this as an instant; an offset-less time once disabled the stall check. */
    @Test
    void timesOnTheWireAreInstantsWithAnOffset() {
        new JobEventPublisher(this.messaging).publishChanged(TENANT_A, 41L, "job.updated");
        String at = (String) sentTo("/topic/jobs." + TENANT_A).get("at");
        assertThat(at).endsWith("Z");
        assertThat(Instant.parse(at)).isNotNull();
    }

    @Test
    void aLogLineCarriesItsRun() {
        new JobEventPublisher(this.messaging).publishLog(TENANT_A, 41L, 7001L, "Read 12 files.");
        Map<String, Object> event = sentTo("/topic/jobs." + TENANT_A);
        assertThat(event).containsEntry("type", "job.log").containsEntry("message", "Read 12 files.");
        assertThat(((Number) event.get("jobQueueId")).longValue()).isEqualTo(7001L);
    }

    @Test
    void anEventWithNoTenantIsNotSentAnywhere() {
        JobEventPublisher publisher = new JobEventPublisher(this.messaging);
        publisher.publishStatus(null, 41L, 7001L, "Running", "x");
        publisher.publishChanged(null, 41L, "job.updated");
        publisher.publishLog(null, 41L, 7001L, "x");
        verify(this.messaging, never()).convertAndSend(anyString(), org.mockito.ArgumentMatchers.any(Object.class));
    }
}
