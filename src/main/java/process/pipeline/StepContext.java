package process.pipeline;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

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

    /**
     * The step's config as the definition writes it, its {{param.*}} and {{run.*}} placeholders unfilled -- what a step
     * keys state by that must outlive one run's values (read_s3's "since the last run" mark). {@link #config()} otherwise.
     */
    default Map<String, Object> configAsWritten() {
        return this.config();
    }

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

    /**
     * MIG-347: the run's {{param.name}}, {{run.date}}, {{run.windowStart}} and {{run.windowEnd}} values by placeholder name --
     * already filled into {@link #config()}; a task's own templates (Templates.ofRun) read them too. Empty outside a run.
     */
    default Map<String, Object> runValues() {
        return Collections.emptyMap();
    }

    /** The run's job (source_job.job_id); null outside a run. */
    default Long jobId() {
        return null;
    }

    /** The pipeline the run's job runs (its task's pipelineId); null outside a run. */
    default String pipelineId() {
        return null;
    }

    /** The name of the pipeline the run's job runs (pipeline.pipeline_name); null outside a run or when it has none. */
    default String pipelineName() {
        return null;
    }

    /**
     * The model this run asks this step to run on (MIG-242, for the engine since the console review of 2026-10-07): an
     * ai-service model option id from the run's "Run with a different AI model...", else the schedule's setting; null for
     * the step's default. Only an AI step reads it; ai-service decides whether the option may be used.
     */
    default String modelProfile() {
        return null;
    }

    /** Where {@link #modelProfile()} came from: "run" or "schedule" (ModelProfiles' words); null for the step's default. */
    default String modelProfileSource() {
        return null;
    }

    /** The run's job's source task (source_task.task_detail_id), by which ai-service finds a step's own allowed models. */
    default Long sourceTaskId() {
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
     * MIG-360: every file this run was started for, in the order they arrived -- a batch of inbox files that waited for
     * the job, or just {@link #inputKey}; empty for a run no file started.
     */
    default List<String> inputKeys() {
        String key = this.inputKey();
        return key == null ? Collections.emptyList() : Collections.singletonList(key);
    }

    /**
     * MIG-364: the approved documents this run was started for (a job's "When a document is approved" trigger) -- one, or
     * the batch that waited -- in the order they were approved; empty for any other run.
     */
    default List<RunDocuments.Document> documents() {
        return Collections.emptyList();
    }

    /**
     * MIG-364: the first of {@link #documents()}' values, for {@code {{document.<field>}}}: the type's fields by key, and
     * id, file_name, type_version, approval, approved_by, date_approved; read from Document Intelligence once per run, the
     * first time a step asks. Empty when no document started the run.
     */
    default Map<String, Object> documentValues() throws Exception {
        return Collections.emptyMap();
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

    /**
     * MIG-419: the files an earlier step of this run made, as its latest attempt recorded them (run_output): an upload with
     * its bucket and key, or a file kept with the run, opened through {@link RunFile#open()}. Empty when the step made none.
     */
    default List<RunFile> runFiles(String stepKey) throws Exception {
        throw new UnsupportedOperationException("This context reads no other step's files.");
    }

    /** MIG-419: a file a step of this run made. */
    final class RunFile {
        public final String name;
        public final String format;
        public final Long bytes;
        /** An upload: the workspace's connection alias and the key; null for a file kept with the run. */
        public final String bucket;
        public final String key;
        private final Callable<InputStream> opener;

        public RunFile(String name, String format, Long bytes, String bucket, String key,
                       Callable<InputStream> opener) {
            this.name = name;
            this.format = format;
            this.bytes = bytes;
            this.bucket = bucket;
            this.key = key;
            this.opener = opener;
        }

        public boolean kept() {
            return this.bucket == null;
        }

        /** A kept file's bytes; refused for an upload, which stays in its bucket. */
        public InputStream open() throws Exception {
            if (this.opener == null) {
                throw new IllegalStateException(this.name + " is in " + this.bucket + ", not kept with the run.");
            }
            return this.opener.call();
        }
    }

    /**
     * MIG-420 (Run Items): a file this step made for one row's item -- recorded in the run's manifest as
     * {@link #recordOutput(RunOutput)} records it, and listed with that item's files. Outside an item run, the manifest alone.
     */
    default void recordOutput(RunOutput output, Map<String, Object> row) throws Exception {
        this.recordOutput(output);
    }

    /**
     * MIG-420: this row's item could not be done by this step, and why, in words a person reads ("the answer did not match
     * the schema"). A per-row step says so for every row it skips, keeps empty or fails on; the step's own on-error still
     * decides what happens to the run. Nothing outside an item run.
     */
    default void itemFailed(Map<String, Object> row, String reason) {
    }

    /** MIG-420: how long this step spent on this row's item (its call, its file), for the item's own time. */
    default void itemTook(Map<String, Object> row, long millis) {
    }

    /**
     * MIG-420: the key a per-row step's call for this row is made under (an AI row, a transcription, a report, an API
     * call's {{call_key}}): the row's number from 1 -- or, in an item run, a short token of the row's item, the same in
     * every attempt and whatever rows were left out, so a retry of some items reuses (and never mixes up) the answers of
     * the others.
     */
    default String rowTag(Map<String, Object> row, int index) {
        return String.valueOf(index + 1);
    }

    /**
     * MIG-421: this step reused, for this row's item, what attempt {@code fromAttempt} of the run made for it (a report with the
     * same data and design): nothing was made or billed again; {@code note} says what ("Report RPT-0018 v1 reused").
     */
    default void itemReused(Map<String, Object> row, String note, int fromAttempt) {
    }

    /** MIG-421: this row's item is flagged ({@code flag}: "check" -- an OCR page to check by hand), {@code note} saying why. */
    default void itemFlagged(Map<String, Object> row, String flag, String note) {
    }

    /**
     * MIG-421: the report a Generate report step of this run made for this row's item from the same data and design
     * ({@code dataHash}), in an earlier attempt or earlier in this one; empty when none (or outside an item run).
     */
    default Optional<KeptReport> keptReport(Map<String, Object> row, int index, String dataHash) {
        return Optional.empty();
    }

    /** MIG-421: keeps the report this step just made for this row's item, so a retry reuses it. Nothing outside an item run. */
    default void reportKept(Map<String, Object> row, int index, String dataHash, KeptReport report) {
    }

    /** MIG-421: a report made for an item: Report Studio's id and version, its label, its PDF's name and pages, the attempt that made it. */
    final class KeptReport {
        public final String reportId;
        public final int version;
        public final String label;
        public final String fileName;
        public final Integer pages;
        public final int madeAttempt;

        public KeptReport(String reportId, int version, String label, String fileName, Integer pages, int madeAttempt) {
            this.reportId = reportId;
            this.version = version;
            this.label = label;
            this.fileName = fileName;
            this.pages = pages;
            this.madeAttempt = madeAttempt;
        }
    }

    /** A line in the step's log (step_log), shown on the step in the console's timeline. */
    void log(String message);

    void warn(String message);
}
