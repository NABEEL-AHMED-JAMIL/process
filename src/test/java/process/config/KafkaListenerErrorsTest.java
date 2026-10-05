package process.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.barco.platform.storage.StorageTopics;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOffExecution;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Event audit E4: a failing record is tried ten times with growing pauses, inside the consumer's poll interval, then
 * goes to its topic's DLT; an unreadable one goes there at once.
 */
class KafkaListenerErrorsTest {

    @Test
    void tenTriesOneToThirtySecondsApartWithinThePollInterval() {
        BackOffExecution pauses = KafkaListenerErrors.backOff().start();
        List<Long> waits = new ArrayList<>();
        for (long next = pauses.nextBackOff(); next != BackOffExecution.STOP; next = pauses.nextBackOff()) {
            waits.add(next);
        }
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L);
        assertThat(waits.stream().mapToLong(Long::longValue).sum()).isLessThan(300_000L);
    }

    @Test
    void deadLettersGoToTheTopicsDlt() {
        assertThat(KafkaListenerErrors.deadLetterOf(StorageTopics.OBJECT_CHANGED).topic()).isEqualTo("platform.storage.object-changed.v1.DLT");
        assertThat(KafkaListenerErrors.deadLetterOf(StorageTopics.OBJECT_CHANGED).partition()).isNegative();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreadableEventIsNotRetried() {
        DefaultErrorHandler handler = (DefaultErrorHandler) new KafkaListenerErrors().kafkaErrorHandler(mock(KafkaTemplate.class));
        // removeClassification answers what the class was classified as: false is "not retryable".
        assertThat(handler.removeClassification(JsonProcessingException.class)).isFalse();
        assertThat(handler.removeClassification(IllegalArgumentException.class)).isFalse();
        assertThat(handler.removeClassification(JsonParseException.class)).as("not listed itself: its superclass is").isNull();
    }
}
