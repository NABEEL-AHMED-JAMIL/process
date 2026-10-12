package process.pipeline.backing;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Send webhook's sender: integration-service's internal webhook endpoints (spec in {@link HttpIntegrationPipelines}),
 * behind their own switch, process.pipeline.integration.webhooks -- a separate bean because they ship after the other
 * internal endpoints.
 */
@Component
public class IntegrationWebhookSender implements WebhookSender {

    private final HttpIntegrationPipelines integration;

    public IntegrationWebhookSender(HttpIntegrationPipelines integration) {
        this.integration = integration;
    }

    @Override
    public Optional<String> unavailable() {
        return this.integration.webhooksUnavailable();
    }

    @Override
    public Sent send(Send send) throws Exception {
        return this.integration.sendWebhook(send);
    }

    @Override
    public Delivery delivery(long tenantId, long webhookId, long deliveryId) throws Exception {
        return this.integration.webhookDelivery(tenantId, webhookId, deliveryId);
    }
}
