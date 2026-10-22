package process.pipeline.items;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One run_item row (MIG-420, V233): an item of a run as one attempt left it. Never handed to a caller as is: its steps
 * point at run datasets by id, which the read side ({@link RunItems}) turns into data the caller may see.
 */
public final class ItemRow {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public Long runItemId;
    public long jobQueueId;
    public int attempt;
    public String itemKey;
    public String itemRef;
    public String parentKey;
    public String label;
    public int position;
    public String status = RUNNING;
    public Integer failedStepIndex;
    public String failedStepKey;
    public String reason;
    public List<StepEntry> steps = new ArrayList<>();
    public int childCount;
    public int childFailed;
    public int fileCount;
    public boolean current = true;
    public Instant startedAt;
    public Instant endedAt;
    public Long durationMs;

    public boolean isSub() {
        return this.parentKey != null;
    }

    public boolean finished() {
        return !RUNNING.equals(this.status);
    }

    /** The entry of step {@code index}, or null. */
    public StepEntry step(int index) {
        for (StepEntry entry : this.steps) {
            if (entry.i == index) {
                return entry;
            }
        }
        return null;
    }

    /** Replaces (or adds) the entry of its step, keeping the steps in order. */
    public void put(StepEntry entry) {
        for (int k = 0; k < this.steps.size(); k++) {
            if (this.steps.get(k).i == entry.i) {
                this.steps.set(k, entry);
                return;
            }
            if (this.steps.get(k).i > entry.i) {
                this.steps.add(k, entry);
                return;
            }
        }
        this.steps.add(entry);
    }

    /** The item failed here, the first time it fails (later steps do not change where it failed). */
    public void fail(int stepIndex, String stepKey, String why, Instant at) {
        if (FAILED.equals(this.status)) {
            return;
        }
        this.status = FAILED;
        this.failedStepIndex = stepIndex;
        this.failedStepKey = stepKey;
        this.reason = cut(why == null || why.trim().isEmpty() ? "The step failed." : why.trim(), 2000);
        this.endedAt = at;
    }

    static String cut(String text, int most) {
        return text == null || text.length() <= most ? text : text.substring(0, most - 1) + "…";
    }

    /** One step as one item saw it: a pointer into the step's kept output, never the data. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class StepEntry {
        public static final String DONE = "done";
        public static final String FAILED = "failed";
        /** The step did not run for the item (an earlier step failed, or the run stopped). */
        public static final String NOT_RUN = "not_run";
        /** A combining step (join, aggregate, dedupe): its output belongs to every item that went in. */
        public static final String SHARED = "shared";
        /** The item's rows are not in the step's output (a filter, a limit): it ends here. */
        public static final String LEFT_OUT = "left_out";

        /** Step index, key, task. */
        public int i;
        public String key;
        public String task;
        public String status;
        public String note;
        /** Milliseconds the step spent on this item, as the step said; null when it did not say. */
        public Long ms;
        /** The run_dataset the item's rows are in at this step (its output, or the input it passed on). */
        public Long ds;
        /** The rows' positions in that dataset, at most {@link ItemTracker#MAX_POSITIONS}. */
        public List<Integer> rows;
        /** How many rows the item has at this step. */
        public Integer n;
        /** A shared output: how many items it belongs to. */
        public Integer sharedBy;
        /** How many files the step made for the item. */
        public Integer files;
        /** A step of a retry that was not run again: the attempt whose output it is. */
        public Integer reusedFrom;
        /** The step execution (for files and audit). */
        public Long se;
        /** The step that named the items: the column that names them ("#": their row number). */
        public String idc;
        /** A fan-out step: the column that names its sub-items. */
        public String subc;

        public StepEntry() {
        }

        StepEntry(int i, String key, String task, String status) {
            this.i = i;
            this.key = key;
            this.task = task;
            this.status = status;
        }

        StepEntry copy() {
            StepEntry c = new StepEntry(this.i, this.key, this.task, this.status);
            c.note = this.note;
            c.ms = this.ms;
            c.ds = this.ds;
            c.rows = this.rows == null ? null : new ArrayList<>(this.rows);
            c.n = this.n;
            c.sharedBy = this.sharedBy;
            c.files = this.files;
            c.reusedFrom = this.reusedFrom;
            c.se = this.se;
            c.idc = this.idc;
            c.subc = this.subc;
            return c;
        }
    }

    /** One run_item_file row: an item's input object, or a file a step made for it. */
    public static final class FileRow {
        public static final String INPUT = "input";
        public static final String OUTPUT = "output";

        public Long runItemFileId;
        public long jobQueueId;
        public int attempt;
        public String itemKey;
        public int stepIndex;
        public String stepKey;
        public String role;
        public Long stepExecutionId;
        public String fileName;
        public String bucketAlias;
        public String objectKey;
        public Long byteCount;
        /** Read side: the manifest row it names (run_output), its format, size, expiry and file id; null for an input object. */
        public Long runOutputId;
        public Long runDatasetId;
        public String format;
        public Instant expiresAt;
        public String fileId;
    }
}
