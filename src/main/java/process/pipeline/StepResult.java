package process.pipeline;

/** What one try of a step produced: its output (nothing, for a step that only acts), and how many records went out. */
public final class StepResult {

    private final Dataset output;
    private final Long recordsOut;
    private final boolean streamed;

    private StepResult(Dataset output, Long recordsOut) {
        this(output, recordsOut, false);
    }

    private StepResult(Dataset output, Long recordsOut, boolean streamed) {
        this.output = output;
        this.recordsOut = recordsOut;
        this.streamed = streamed;
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
