package process.pipeline.backing;

import java.util.Map;
import java.util.Optional;

/**
 * MIG-346: integration-service's webhooks, as the Send webhook step calls them. Core never posts to a URL itself: it hands
 * integration-service the workspace's subscription (by id) and the data, and integration-service delivers it as it
 * delivers every webhook -- signed, retried with backoff, through its outbound guard, logged and metered -- and only to a
 * subscription of that workspace that is active and takes the run.step.sent event type.
 */
public interface WebhookSender {

    /** The event type a step's delivery has: a subscription must take it to receive one. */
    String EVENT_TYPE = "run.step.sent";

    /** Why webhooks cannot be sent from here now; empty when they can. */
    Optional<String> unavailable();

    /** Queues the delivery (once per event id: the same id again answers the first delivery, replay true). */
    Sent send(Send send) throws Exception;

    /** A delivery's state now. */
    Delivery delivery(long tenantId, long webhookId, long deliveryId) throws Exception;

    final class Send {
        public long tenantId;
        public long jobQueueId;
        public String stepKey;
        public int attempt;
        public long webhookId;
        public String eventId;
        public String subject;
        public Map<String, Object> data;
    }

    final class Sent {
        public String eventId;
        public long deliveryId;
        public String status;
        public boolean replay;
    }

    final class Delivery {
        /** Pending, Retrying, Delivered or Failed. */
        public String status;
        public int attempts;
        public Integer responseStatus;
        public String lastError;
    }
}
