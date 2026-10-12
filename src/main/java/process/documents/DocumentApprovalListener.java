package process.documents;

import org.barco.platform.correlation.CorrelationScope;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Core's consumer of Document Intelligence's approvals (MIG-364). Each event starts the jobs its workspace set to start
 * when a document of that type is approved, once per document and version (DocumentTriggerService).
 *
 * Nobody is signed in on a listener's thread, so the work runs as the event's workspace and nothing else
 * (RowSecurity.forTenant): its triggers, its jobs, its runs -- never an across-tenants grant. It runs under the event's
 * traceId, the approval's own, so one id joins the reviewer's request, ai-service and this run.
 *
 * An unreadable event is logged and skipped -- no retry makes it readable. A database that cannot be written throws, and
 * the container redelivers; the approval's unique row makes that safe. Its own group, from the beginning of the topic the
 * first time, so an approval published before this listener first ran is not lost (only jobs with a trigger act on it).
 */
@Component
public class DocumentApprovalListener {

    private static final Logger logger = LoggerFactory.getLogger(DocumentApprovalListener.class);

    private final DocumentTriggerService triggers;

    public DocumentApprovalListener(DocumentTriggerService triggers) {
        this.triggers = triggers;
    }

    @KafkaListener(id = "document-approvals", topics = DocumentTopics.DOCUMENT_APPROVED, groupId = "process-document-trigger",
        autoStartup = "${documents.events.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onApproval(String message) {
        DocumentApproved approved;
        try {
            approved = DocumentApproved.parse(message);
        } catch (IllegalArgumentException unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", DocumentTopics.DOCUMENT_APPROVED, unreadable.getMessage());
            return;
        }
        try (CorrelationScope scope = CorrelationScope.open(approved.getTraceId())) {
            List<DocumentTriggerService.Outcome> outcomes = RowSecurity.forTenant(approved.getTenantId(), () -> this.triggers.onApproval(approved));
            if (!outcomes.isEmpty()) {
                logger.info("Approved {} in workspace {}: {} job(s) matched: {}.", approved, approved.getTenantId(), outcomes.size(), outcomes);
            }
        }
    }
}
