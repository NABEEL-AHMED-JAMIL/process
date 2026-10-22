package process.pipeline.registry;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a task does with a run's items (MIG-420, the Run Items view): an item is one input file, recording, document or
 * row of a per-row step. Only a pipeline with an {@link #EACH} or {@link #FAN_OUT} step is an item pipeline; its first
 * {@link #SOURCE}, {@link #EACH} or {@link #FAN_OUT} step names its items. A task reading a big table whole is none of
 * these, so it never makes items.
 */
public enum ItemRole {

    /** Finds the items: one row per object (Read S3 as a list) or per document (Read documents), by an identity column. */
    SOURCE("source", "Finds the items: one row per file or document."),
    /** Works on each item on its own (a recording transcribed, a prompt per row, one file per row): items can fail alone. */
    EACH("each", "Works item by item: each item can fail, be retried and be billed on its own."),
    /** Turns each item into several sub-items (a PDF into its pages), each with its own status and retry. */
    FAN_OUT("fan_out", "Splits each item into sub-items (a document into its pages)."),
    /** Combines items (join, aggregate, dedupe): its output is shared by the items that went in. */
    SHARED("shared", "Combines items: its output is shared by every item that went in."),
    /** Passes rows on: each row keeps its item. */
    PASS("pass", "Keeps each row with its item.");

    private final String word;
    private final String note;

    ItemRole(String word, String note) {
        this.word = word;
        this.note = note;
    }

    @JsonValue
    public String word() {
        return this.word;
    }

    public String note() {
        return this.note;
    }

    /** Whether a pipeline with this step is an item pipeline. */
    public boolean makesItemPipeline() {
        return this == EACH || this == FAN_OUT;
    }

    /** Whether this step can be where a run's items are first named. */
    public boolean namesItems() {
        return this == SOURCE || this == EACH || this == FAN_OUT;
    }
}
