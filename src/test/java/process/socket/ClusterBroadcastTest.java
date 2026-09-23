package process.socket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The bridge that lets a second instance exist (MIG-44/97): what one instance sends, every
 * instance delivers to the browsers connected to it.
 */
class ClusterBroadcastTest {

    private final SimpMessagingTemplate local = mock(SimpMessagingTemplate.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    private final ClusterBroadcast bridge = new ClusterBroadcast(this.local, this.redis);

    private String published() {
        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(this.redis).convertAndSend(eq(ClusterBroadcast.CHANNEL), envelope.capture());
        return envelope.getValue();
    }

    private static DefaultMessage onTheChannel(String envelope) {
        return new DefaultMessage(ClusterBroadcast.CHANNEL.getBytes(StandardCharsets.UTF_8), envelope.getBytes(StandardCharsets.UTF_8));
    }

    /** DEF-061: one event is one message between instances, however many local audiences it has. */
    @Test
    void oneEventForTwoAudiencesIsOneMessageOnTheBridge() {
        this.bridge.toTopics(Arrays.asList("/topic/jobs.2901", "/topic/jobs.all"), "{\"type\":\"job.status\"}");

        verify(this.local).convertAndSend("/topic/jobs.2901", "{\"type\":\"job.status\"}");
        verify(this.local).convertAndSend("/topic/jobs.all", "{\"type\":\"job.status\"}");
        verify(this.redis, times(1)).convertAndSend(eq(ClusterBroadcast.CHANNEL), anyString());
        JsonObject envelope = new Gson().fromJson(this.published(), JsonObject.class);
        assertThat(envelope.getAsJsonArray("destinations")).hasSize(2);
    }

    @Test
    void anEventFromAnotherInstanceIsDeliveredToTheBrowsersHere() {
        ClusterBroadcast other = new ClusterBroadcast(mock(SimpMessagingTemplate.class), this.redis);
        other.toTopics(Arrays.asList("/topic/jobs.2901", "/topic/jobs.all"), "{\"n\":1}");

        this.bridge.onMessage(onTheChannel(this.published()), null);

        verify(this.local).convertAndSend("/topic/jobs.2901", "{\"n\":1}");
        verify(this.local).convertAndSend("/topic/jobs.all", "{\"n\":1}");
    }

    /** Redis echoes a publish back to its sender, which has already delivered locally. */
    @Test
    void itsOwnEchoIsNotDeliveredTwice() {
        this.bridge.toTopics(Arrays.asList("/topic/jobs.2901"), "{}");

        this.bridge.onMessage(onTheChannel(this.published()), null);

        verify(this.local, times(1)).convertAndSend("/topic/jobs.2901", "{}");
    }

    @Test
    void aUserMessageFromAnotherInstanceGoesToThatUsersSessionsHere() {
        ClusterBroadcast other = new ClusterBroadcast(mock(SimpMessagingTemplate.class), this.redis);
        other.toUser("ops@medaxis.example", "/queue/notifications", "{\"unread\":3}");

        this.bridge.onMessage(onTheChannel(this.published()), null);

        verify(this.local).convertAndSendToUser("ops@medaxis.example", "/queue/notifications", "{\"unread\":3}");
    }

    /** With one instance and Redis down, the browsers on this instance still get their events. */
    @Test
    void withRedisDownThisInstanceStillDelivers() {
        doThrow(new IllegalStateException("redis down")).when(this.redis).convertAndSend(anyString(), any());

        assertThatCode(() -> this.bridge.toUser("ops@medaxis.example", "/queue/reply", "{}")).doesNotThrowAnyException();
        verify(this.local).convertAndSendToUser("ops@medaxis.example", "/queue/reply", "{}");
    }

    @Test
    void somethingUnreadableOnTheChannelIsIgnored() {
        assertThatCode(() -> {
            this.bridge.onMessage(onTheChannel("not json"), null);
            this.bridge.onMessage(onTheChannel("{\"origin\":\"x\"}"), null);
        }).doesNotThrowAnyException();
        verify(this.local, never()).convertAndSend(anyString(), any(Object.class));
        verify(this.local, never()).convertAndSendToUser(anyString(), anyString(), any());
    }
}
