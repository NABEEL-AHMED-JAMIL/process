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

    // ------------------------------------------------------------------------------------------------ MIG-421 (v2)

    /**
     * A schedule's items across its runs -- each run's current top-level row of every item the query names -- newest run
     * first, at most {@code limit}: candidates, which the read side checks again against what the person may see (their
     * masks, their runs). From the item records alone (key, label, fields), never the outputs.
     */
    List<AcrossRow> acrossRuns(long jobId, AcrossQuery query, int limit);

    /** The PDF reports of these items in these runs: the kept report (made or reused), else the item's latest PDF file. */
    List<KeptReport> reportsOf(Collection<Long> jobQueueIds, Collection<String> itemKeys);

    /** A report a Generate report step made for one item of the run, with this hash; empty when none. */
    Optional<KeptReport> keptReport(long jobQueueId, String stepKey, String itemTag, String dataHash);

    /** Keeps a report a step made for an item (once per run, step, item and hash). */
    void keepReport(KeptReport report);

    /** Writes the values read for these items (run_item.fields, fields_ds) -- an older item's, read on first view. */
    void saveFields(List<ItemRow> rows);

    /** What the across-runs list asks: an exact item key, or a text, or a field and a value; a status; a time window. */
    final class AcrossQuery {
        /** An exact item key ("All runs of this item"). */
        public String key;
        /** Text in the item's key, name or values. */
        public String text;
        /** A field ("risk", or "status.risk") and the value it must equal (ignoring case). */
        public String field;
        public String value;
        /** done, failed, running, check; null: any. */
        public String status;
        /** The runs started in this window; null: no bound. */
        public Instant from;
        public Instant to;
    }

    /** An item's row in one run, with the run's facts. */
    final class AcrossRow {
        public ItemRow item;
        public long jobId;
        public String runStatus;
        public int runAttempt;
        public Instant runStarted;
        public Instant runEnded;
        /** How the run started (RunFacets' trigger key, its first attempt's): schedule, manual, file, form, api... */
        public String trigger;
        /** A manual run with values (Run with...). */
        public boolean withValues;
    }

    /** A report made for an item (run_item_report), or -- for an older run -- the item's PDF file. */
    final class KeptReport {
        public long jobQueueId;
        public String stepKey;
        public String itemTag;
        public String itemKey;
        public String dataHash;
        public String reportId;
        public int reportVersion;
        public String reportLabel;
        public String fileName;
        public Integer pages;
        public int madeAttempt;
        /** The item's file the report is (run_item_file), when known: the console opens it. */
        public Long fileRef;
        /** ZT-I24: the PDF's sha256 as the run kept it (run_output), for an older run's report to be named by its number. */
        public String sha256;
    }

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
