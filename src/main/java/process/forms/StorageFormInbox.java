package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.storage.remote.StorageServiceClient;

/**
 * {@link FormInbox} from storage-service's GET /storage.json/inbox, with the caller's own token (every member may read
 * it): configured, the alias, and whether its connection is still active. Anything else -- storage down, no inbox, a
 * retired connection -- is a sentence the submission records as the reason its run did not start.
 */
@Component
public class StorageFormInbox implements FormInbox {

    static final String NO_INBOX = "This workspace has no inbox, so the submission could not be handed to its job. "
        + "A workspace admin chooses the inbox's storage connection under Documents > Inbox.";
    static final String INACTIVE = "The workspace inbox's storage connection is not active, so the submission could not be "
        + "handed to its job.";

    private static final Logger logger = LoggerFactory.getLogger(StorageFormInbox.class);

    private final StorageServiceClient storage;

    public StorageFormInbox(StorageServiceClient storage) {
        this.storage = storage;
    }

    @Override
    public Location locate() {
        JsonNode inbox;
        try {
            inbox = this.storage.guardedGet("/inbox", null);
        } catch (RuntimeException unreachable) {
            logger.warn("The workspace inbox could not be read for a form submission: {}", unreachable.getMessage());
            return Location.none("The workspace inbox could not be read (" + unreachable.getMessage() + "), so the submission "
                + "could not be handed to its job.");
        }
        return of(inbox);
    }

    static Location of(JsonNode inbox) {
        if (inbox == null || !inbox.path("configured").asBoolean(false) || inbox.path("alias").asText("").trim().isEmpty()) {
            return Location.none(NO_INBOX);
        }
        if (inbox.has("connectionActive") && !inbox.path("connectionActive").asBoolean(true)) {
            return Location.none(INACTIVE);
        }
        return Location.at(inbox.path("alias").asText().trim());
    }
}
