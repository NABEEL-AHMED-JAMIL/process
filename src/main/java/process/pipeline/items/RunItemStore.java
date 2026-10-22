package process.pipeline.items;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run's items as the step engine writes them and the console and the customer API read them (MIG-420, V233: run_item,
 * run_item_file). Every call is the caller's workspace's -- the engine works inside RowSecurity.forTenant of the run's
 * tenant, a reader as itself -- and tenant_id is filled from the run.
 */
public interface RunItemStore {

    /** Writes these rows (insert, or update by run, key and attempt); each earlier attempt's row of the same item stops being current. */
    void save(List<ItemRow> rows);

    /** Every row of the run from an attempt before {@code attempt} stops being current (a restart's items replace them all). */
    void retireBefore(long jobQueueId, int attempt);

    void addFiles(List<ItemRow.FileRow> files);

    /** The run's items as they stand: each item's current row, top-level items and sub-items, in position order. */
    List<ItemRow> current(long jobQueueId);

    /** Every row of one attempt. */
    List<ItemRow> ofAttempt(long jobQueueId, int attempt);

    /** Every version of one item, newest attempt first. */
    List<ItemRow> versions(long jobQueueId, String itemKey);

    Optional<ItemRow> byId(long runItemId);

    /** An item's files at one attempt (its own, and those a reused step made), filtered, a page. */
    List<ItemRow.FileRow> files(long jobQueueId, String itemKey, int attempt, String stepKey, String search, int offset, int limit);

    int countFiles(long jobQueueId, String itemKey, int attempt, String stepKey, String search);

    /**
     * ZT-I16: a document's files with its sub-items' (a page's image and text), each at its current version, filtered, a page, in
     * step and page order.
     */
    List<ItemRow.FileRow> familyFiles(long jobQueueId, String parentKey, String stepKey, String search, int offset, int limit);

    int countFamilyFiles(long jobQueueId, String parentKey, String stepKey, String search);

    Optional<ItemRow.FileRow> file(long runItemFileId);

    /** Per run: {items, done, failed, running} over its current top-level items; a run with none is left out. */
    Map<Long, int[]> counts(Collection<Long> jobQueueIds);

    /** The runs of the caller's workspace that ended since {@code since} with failed items, newest first. */
    List<Attention> attention(Instant since, int limit);

    /** run.item.completed for this item (api_event_out kind 'item'), in the caller's transaction. */
    void journalCompleted(long runItemId);

    /** A run with failed items, for Home's Needs attention. */
    final class Attention {
        public long jobQueueId;
        public long jobId;
        public int failed;
        public int items;
        public Instant endedAt;
        public List<String> labels;
        public List<String> stepKeys;
    }
}
