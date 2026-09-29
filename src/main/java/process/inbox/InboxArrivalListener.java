package process.inbox;

import org.barco.platform.correlation.CorrelationScope;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Core's consumer of Storage's inbox arrivals (MIG-239). Each event starts the jobs its workspace set to trigger on the
 * file, once per arrival (InboxTriggerService).
 *
 * Nobody is signed in on a listener's thread, so the work runs as the event's workspace and nothing else
 * (RowSecurity.forTenant): its triggers, its jobs, its runs -- never an across-tenants grant. It runs under the event's
 * traceId, the id of the upload that raised it, so one id joins the console's request, Storage and this run.
 *
 * An unreadable event is logged and skipped -- no retry makes it readable. A database that cannot be written throws,
 * and the container redelivers; the arrival's unique row makes that safe. Its own group, from the beginning of the topic
 * the first time, so an arrival published before this listener first ran is not lost.
 */
@Component
public class InboxArrivalListener {

    private static final Logger logger = LoggerFactory.getLogger(InboxArrivalListener.class);

    private final InboxTriggerService triggers;

    public InboxArrivalListener(InboxTriggerService triggers) {
        this.triggers = triggers;
    }

    @KafkaListener(id = "inbox-arrivals", topics = InboxTopics.INBOX_ARRIVED, groupId = "process-inbox-trigger",
        autoStartup = "${inbox.events.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onArrival(String message) {
        InboxArrival arrival;
        try {
            arrival = InboxArrival.parse(message);
        } catch (IllegalArgumentException unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", InboxTopics.INBOX_ARRIVED, unreadable.getMessage());
            return;
        }
        try (CorrelationScope scope = CorrelationScope.open(arrival.getTraceId())) {
            List<InboxTriggerService.Outcome> outcomes = RowSecurity.forTenant(arrival.getTenantId(), () -> this.triggers.onArrival(arrival));
            logger.info("Inbox arrival {} in workspace {} ({}): {} job(s) matched: {}.", arrival.getArrivalId(), arrival.getTenantId(),
                arrival.getKey(), outcomes.size(), outcomes);
        }
    }
}
