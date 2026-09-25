package process.outbox;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.pojo.KafkaConnectionProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-94: the dispatch relay hands a run to the broker under the run's own id -- the X-Correlation-Id header the
 * dispatch stamped (MIG-95) -- so the hand-over, the move to Start and any failure are logged and audited under
 * the id the worker and every callback carry. The relay's thread belongs to no request; before this it logged
 * under nothing.
 */
class DispatchRelayCorrelationTest {

    private static final String RUN = "01J8ZRUN00000000000000000B";

    @AfterEach
    void tidy() {
        CorrelationId.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunIsHandedOverAndMovedToStartUnderItsOwnId() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AtomicReference<String> atSend = new AtomicReference<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            atSend.set(CorrelationId.current());
            SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
            future.set(new SendResult<>(call.getArgument(0),
                new RecordMetadata(new TopicPartition("etl.jobs", 0), 41L, 0L, 0L, 0L, 0, 0)));
            return future;
        });
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        when(templates.getTemplate(any())).thenReturn(kafka);
        KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);
        when(resolver.require(any(), any())).thenReturn(new KafkaConnectionProfile());
        DispatchOutcomes outcomes = mock(DispatchOutcomes.class);
        AtomicReference<String> atStart = new AtomicReference<>();
        doAnswer(call -> {
            atStart.set(CorrelationId.current());
            return null;
        }).when(outcomes).published(anyLong(), anyInt(), anyLong());
        DispatchRelay relay = new DispatchRelay(mock(JdbcTemplate.class), TransactionOperations.withoutTransaction(),
            resolver, templates);
        relay.setOutcomes(outcomes);

        assertThat(relay.publish(row("{\"x-tenant-id\":\"2905\",\"X-Correlation-Id\":\"" + RUN + "\"}")))
            .isEqualTo(DispatchRelay.Outcome.PUBLISHED);

        assertThat(atSend.get()).isEqualTo(RUN);
        assertThat(atStart.get()).isEqualTo(RUN);
        assertThat(CorrelationId.current()).as("the relay's thread is left with nothing").isNull();
    }

    @Test
    void aRowWithoutAUsableIdIsStillPublished() {
        assertThat(DispatchRelay.correlationIdOf("{\"x-tenant-id\":\"2905\"}")).isNull();
        assertThat(DispatchRelay.correlationIdOf("not json")).isNull();
        assertThat(DispatchRelay.correlationIdOf(null)).isNull();
    }

    private static DispatchRelay.Row row(String headers) {
        DispatchRelay.Row row = new DispatchRelay.Row();
        row.outboxId = 9L;
        row.jobQueueId = 7301L;
        row.attempt = 1;
        row.tenantId = 2905L;
        row.topic = "etl.jobs";
        row.messageKey = "k";
        row.payload = "{}";
        row.headers = headers;
        return row;
    }
}
