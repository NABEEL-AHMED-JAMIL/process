package process.pipeline;

/** What one try of a step produced: its output (nothing, for a step that only acts), and how many records went out. */
public final class StepResult {

    private final Dataset output;
    private final Long recordsOut;

    private StepResult(Dataset output, Long recordsOut) {
        this.output = output;
        this.recordsOut = recordsOut;
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
