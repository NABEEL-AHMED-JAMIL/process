package process.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-45: what the dispatch relay does with a run whose workspace and task type resolve to no Kafka
 * connection -- characterised as it stands before the fallback is removed.
 */
class DispatchRelayUnresolvedRouteTest {

    @Test
    @SuppressWarnings("unchecked")
    void today_anUnresolvedRouteIsSentOnTheFallbackTemplate() {
        KafkaTemplate<String, String> fallback = mock(KafkaTemplate.class);
        when(fallback.send(any(ProducerRecord.class))).thenAnswer(call -> {
            SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
            future.set(new SendResult<>(call.getArgument(0), new RecordMetadata(new TopicPartition("etl.jobs", 0), 3L, 0L, 0L, 0L, 0, 0)));
            return future;
        });
        KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());
        DispatchOutcomes outcomes = mock(DispatchOutcomes.class);
        DispatchRelay relay = new DispatchRelay(mock(JdbcTemplate.class), TransactionOperations.withoutTransaction(),
            resolver, new KafkaTemplateProvider(null, fallback, null, null));
        relay.setOutcomes(outcomes);

        assertThat(relay.publish(row())).isTrue();

        verify(fallback).send(any(ProducerRecord.class));
        verify(outcomes).published(anyLong(), anyInt(), anyLong());
    }

    static DispatchRelay.Row row() {
        DispatchRelay.Row row = new DispatchRelay.Row();
        row.outboxId = 11L;
        row.jobQueueId = 4511L;
        row.attempt = 1;
        row.tenantId = 4501L;
        row.sourceTaskTypeId = 4502L;
        row.topic = "etl.jobs";
        row.messageKey = "k";
        row.payload = "{}";
        row.headers = "{}";
        return row;
    }
}
