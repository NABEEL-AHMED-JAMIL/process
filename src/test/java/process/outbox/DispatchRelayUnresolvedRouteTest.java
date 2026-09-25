package process.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import process.config.KafkaConnectionResolver;
import process.config.KafkaRouteUnresolvedException;
import process.config.KafkaTemplateProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-45: a run whose workspace and task type resolve to no Kafka connection is sent nowhere. The relay
 * asks no template for it -- there is no fallback broker any more -- abandons the outbox row with the
 * reason, and reports the run unrouted, which fails it with that reason as its status line.
 */
class DispatchRelayUnresolvedRouteTest {

    private static final String REASON =
        "No Kafka connection is set for task type 'claims-intake' in this workspace: set a route or a default connection.";

    @Test
    void anUnresolvedRouteIsRefusedAndTheRunReportedUnrouted() {
        KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);
        when(resolver.require(4501L, 4502L)).thenThrow(new KafkaRouteUnresolvedException(REASON));
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DispatchOutcomes outcomes = mock(DispatchOutcomes.class);
        DispatchRelay relay = new DispatchRelay(jdbc, TransactionOperations.withoutTransaction(), resolver, templates);
        relay.setOutcomes(outcomes);

        assertThat(relay.publish(row())).isEqualTo(DispatchRelay.Outcome.UNROUTED);

        verifyNoInteractions(templates);
        verify(jdbc).update(contains("abandoned_at = now()"), eq(REASON), eq(11L));
        verify(outcomes).unrouted(4511L, 1, REASON);
        verify(outcomes, never()).published(anyLong(), anyInt(), anyLong());
        verify(outcomes, never()).publishFailed(anyLong(), anyInt(), any());
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
