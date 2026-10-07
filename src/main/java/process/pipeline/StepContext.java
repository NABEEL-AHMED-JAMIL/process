package process.pipeline;

import java.util.List;
import java.util.Map;

/** What a step task is handed for one try: its run, its config, its input, and its log. */
public interface StepContext {

    long tenantId();

    long jobQueueId();

    /** The run's attempt (job_queue.attempt): a retried run runs its steps again under the next attempt. */
    int attempt();

    String stepKey();

    /** Which try of this step within the attempt, from 1 (the step's retry.maxAttempts bounds it). */
    int tryNumber();

    Map<String, Object> config();

    /** The step's input: the output it names, else the latest output before it, else the source. Never null. */
    Dataset input();

    /** MIG-344: how many rows the input has, without holding it (a streamed input may be bigger than a step can hold). */
    default long inputSize() {
        return this.input().size();
    }

    /** MIG-344: the input's first row, or null when it has none, without reading the rest of it. */
    default Map<String, Object> firstInputRow() throws Exception {
        Dataset input = this.input();
        return input == null || input.size() == 0 ? null : input.getRows().get(0);
    }

    /** The run's job (source_job.job_id); null outside a run. */
    default Long jobId() {
        return null;
    }

    /** The pipeline the run's job runs (its task's pipelineId); null outside a run. */
    default String pipelineId() {
        return null;
    }

    /** Who owns the run's job (source_job.created_by): whom an "owner" notice goes to; null when nobody does. */
    default Long jobOwnerUserId() {
        return null;
    }

    /** The storage alias of the file this run was started for (an inbox arrival, MIG-239); null for any other run. */
    default String inputBucket() {
        return null;
    }

    /** The key of the file this run was started for (an inbox arrival, MIG-239); null for any other run. */
    default String inputKey() {
        return null;
    }

    /**
     * The output of an earlier step of this run, by its key (a join's other side, MIG-231). An exception when that step
     * made none (it failed and continued, or only acted) or is not earlier.
     */
    default Dataset dataset(String stepKey) throws Exception {
        throw new UnsupportedOperationException("This context reads no other step's output.");
    }

    /**
     * Keeps a file this step made with the run (MIG-231's Save File): written beside the run's datasets, recorded as a
     * run_dataset named by the file, and removed with them when the run's datasets expire. {@code fileName} is a plain
     * name (letters, digits, '.', '-', '_'). Returns nothing a step may hand out: the storage key is the platform's.
     */
    default void keepFile(String fileName, byte[] content, long rows, List<String> columns) throws Exception {
        throw new UnsupportedOperationException("This context keeps no files.");
    }

    /**
     * Records a file this step wrote in the run's result manifest (run_output, Wave 4): a file kept with {@link #keepFile}
     * (named by it), or an object uploaded to a workspace bucket. Once per step per attempt: a later try's record
     * replaces an earlier one's. Nothing outside a run.
     */
    default void recordOutput(RunOutput output) throws Exception {
    }

    /** A line in the step's log (step_log), shown on the step in the console's timeline. */
    void log(String message);

    void warn(String message);
}
