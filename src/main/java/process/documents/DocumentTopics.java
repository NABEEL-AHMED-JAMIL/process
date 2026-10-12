package process.documents;

/**
 * Document Intelligence's approval topic (MIG-270, ai-service's DocumentApproval.TOPIC), consumed by Core since MIG-364.
 * One producer, one consumer: the wire is pinned by the same fixture in both repositories (document-approved-v1.json).
 */
public final class DocumentTopics {

    /** A document was approved (by a reviewer, or on its own); keyed by the workspace (tenant id). */
    public static final String DOCUMENT_APPROVED = "platform.document.approved.v1";

    private DocumentTopics() {
    }
}
