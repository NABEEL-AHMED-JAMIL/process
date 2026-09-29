package process.inbox;

/**
 * Storage's inbox topic (MIG-239), as storage-service's InboxTopics names it. Not in platform-commons: one producer, one
 * consumer, and the wire is pinned by the same fixture in both repositories (inbox-arrived-v1.json).
 */
public final class InboxTopics {

    /** A file arrived in a workspace's inbox; keyed by the workspace (tenant id). */
    public static final String INBOX_ARRIVED = "platform.storage.inbox-arrived.v1";

    private InboxTopics() {
    }
}
