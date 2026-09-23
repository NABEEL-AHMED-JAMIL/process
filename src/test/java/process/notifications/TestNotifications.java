package process.notifications;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import process.emailer.EmailMessagesFactory;
import process.model.service.NotificationCenterService;
import process.socket.JobEventPublisher;
import process.socket.NotificationService;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The real port implementation over a test's own mocks.
 *
 * Tests written before the port verify the socket publisher, the notification centre and the mailer
 * directly. Handing the code under test this port instead of a mock of it keeps every one of those
 * assertions unchanged -- and they now prove the port delivers exactly what the direct calls did.
 * Pass null for any collaborator a test does not care about.
 */
public final class TestNotifications {

    private TestNotifications() {
    }

    @SuppressWarnings("unchecked")
    public static InProcessNotifications inProcess(JobEventPublisher jobEvents, NotificationService userPush,
        NotificationCenterService notificationCenter, EmailMessagesFactory mailer) {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(values);
        // Every key is new unless a test says otherwise: the once-per-outcome guard is tested on its own.
        lenient().when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        return new InProcessNotifications(
            jobEvents != null ? jobEvents : mock(JobEventPublisher.class),
            userPush != null ? userPush : mock(NotificationService.class),
            notificationCenter != null ? notificationCenter : mock(NotificationCenterService.class),
            mailer != null ? mailer : mock(EmailMessagesFactory.class),
            redis);
    }
}
