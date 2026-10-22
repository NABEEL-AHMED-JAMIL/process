package process.pipeline;

import java.util.Map;
import java.util.LinkedHashMap;

/** What one try of a step produced: its output (nothing, for a step that only acts), and how many records went out. */
public final class StepResult {

    private final Dataset output;
    private final Long recordsOut;
    private final boolean streamed;
    /** MIG-346: the step asks for an approval: the engine pauses the run here until the reviewers decide. */
    private final ApprovalAsk approval;

    private StepResult(Dataset output, Long recordsOut) {
        this(output, recordsOut, false);
    }

    private StepResult(Dataset output, Long recordsOut, boolean streamed) {
        this(output, recordsOut, streamed, null);
    }

    private StepResult(Dataset output, Long recordsOut, boolean streamed, ApprovalAsk approval) {
        this.output = output;
        this.recordsOut = recordsOut;
        this.streamed = streamed;
        this.approval = approval;
    }

    /**
     * MIG-346: the run waits here for an approval (the Approval step). The engine records the request and pauses the run;
     * approved, the run continues from the next step on what this step read; rejected, it fails with the reason.
     */
    public static StepResult awaiting(ApprovalAsk ask, long records) {
        return new StepResult(null, records, false, ask);
    }

    /** The approval this step asks for, or null for every other step. */
    public ApprovalAsk getApproval() {
        return approval;
    }

    public boolean isAwaiting() {
        return approval != null;
    }

    /** What an Approval step asks: the task's title, what to check, fields shown, who decides (null: the pipeline's), how long. */
    public static final class ApprovalAsk {
        public String title;
        public String instructions;
        public Map<String, String> fields = new LinkedHashMap<>();
        /** role or team, with its value; null for the pipeline's review reviewers, else the administrators. */
        public String reviewersKind;
        public String reviewersValue;
        public int expiresAfterHours;
    }

    /** MIG-344: a streaming try's output is the {@code rows} rows it put in its sink ({@link StreamContext#output}). */
    public static StepResult streamed(long rows) {
        return new StepResult(null, rows, true);
    }

    /** Whether the output is in the streaming sink rather than {@link #getOutput}. */
    public boolean isStreamed() {
        return streamed;
    }

    /** An output, whose row count is the records out. */
    public static StepResult of(Dataset output) {
        return new StepResult(output, output == null ? null : (long) output.size());
    }

    /** No dataset: the step acted (sent, wrote) on {@code records} records. The next step reads what this one read. */
    public static StepResult nothing(Long records) {
        return new StepResult(null, records);
    }

    public Dataset getOutput() {
        return output;
    }

    public Long getRecordsOut() {
        return recordsOut;
    }
}
