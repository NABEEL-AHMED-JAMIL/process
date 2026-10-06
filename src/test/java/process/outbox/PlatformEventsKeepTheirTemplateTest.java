package process.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import process.config.KafkaTemplateProvider;
import process.identity.InternalKafkaPublishRestApi;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-45 removed the fallback template from workspace dispatch. The platform's own events are a different
 * path and must not have gone with it: the outbox relay publishes platform.* events on the application's
 * own KafkaTemplate bean (spring.kafka.*), handed to it directly, never through the workspace resolver --
 * so a missing route or default refuses a workspace's send and cannot touch a platform event. And the other
 * way round: the workspace side has no way left to reach that template.
 */
class PlatformEventsKeepTheirTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void theOutboxRelayPublishesOnTheApplicationsOwnTemplate() {
        KafkaTemplate<String, String> platform = mock(KafkaTemplate.class);

        OutboxRelay relay = new OutboxConfig().outboxRelay(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
            platform, mock(OutboxWriter.class), 1000);

        assertThat(ReflectionTestUtils.getField(relay, "kafka")).isSameAs(platform);
    }

    @Test
    void theWorkspaceSideCannotReachTheApplicationsTemplate() {
        for (Class<?> workspaceSide : new Class<?>[] { KafkaTemplateProvider.class, DispatchRelay.class,
            InternalKafkaPublishRestApi.class }) {
            for (Constructor<?> constructor : workspaceSide.getDeclaredConstructors()) {
                assertThat(Arrays.asList(constructor.getParameterTypes()))
                    .as("%s takes no KafkaTemplate to fall back on", workspaceSide.getSimpleName())
                    .doesNotContain(KafkaTemplate.class);
            }
        }
    }
}
