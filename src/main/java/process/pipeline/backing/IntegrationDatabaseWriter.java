package process.pipeline.backing;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Write Database's writer: integration-service's write endpoint (spec in {@link HttpIntegrationPipelines}), behind its
 * own switch, process.pipeline.integration.database-write, off -- a separate bean because its availability is not the
 * read endpoints'.
 */
@Component
public class IntegrationDatabaseWriter implements DatabaseWriter {

    private final HttpIntegrationPipelines integration;

    public IntegrationDatabaseWriter(HttpIntegrationPipelines integration) {
        this.integration = integration;
    }

    @Override
    public Optional<String> unavailable() {
        return this.integration.writeUnavailable();
    }

    @Override
    public long write(WriteCall call) throws Exception {
        return this.integration.write(call);
    }
}
