package process.pipeline;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run's steps as the engine writes them and the console reads them (MIG-230): step_execution, step_log and
 * run_dataset (V180, V183). Every write is the run's workspace's -- the engine works inside RowSecurity.forTenant of the
 * run's tenant, and tenant_id is filled from the parent row.
 */
public interface StepStore {

    /**
     * Takes a queued run for the step engine: latched as sent (job_send) and prepared, so neither the pre-dispatch
     * phase nor the dispatcher takes it again. Only a run still queued, unsent and unprepared is taken; false when
     * another replica, the dispatcher or an operator got there first.
     */
    boolean claimForEngine(long jobQueueId, String correlationId);

    /**
     * The definition the run's latest attempt with steps followed, if one did: a retry under the job's policy follows the
     * same one. MIG-345: a resumed or restarted attempt may follow a newer one (run_attempt), and from then on its own.
     */
    Optional<Long> pinnedDefinition(long jobQueueId);

    /**
     * MIG-326: {@link #pinnedDefinition} for a page of runs at once, by run id (a run that follows none is left out). The
     * default asks one run at a time; the JDBC store answers in one query.
     */
    default Map<Long, Long> pinnedDefinitions(Collection<Long> jobQueueIds) {
        Map<Long, Long> pinned = new HashMap<>();
        for (Long id : jobQueueIds) {
            this.pinnedDefinition(id).ifPresent(definition -> pinned.put(id, definition));
        }
        return pinned;
    }

    /** One row per step of this attempt, all Queue, in order; the rows' ids in the steps' order. Idempotent. */
    List<Long> plan(long jobQueueId, int attempt, long pipelineDefinitionId, List<Planned> steps);

    void started(long stepExecutionId, Long recordsIn);

    void tried(long stepExecutionId, int tries);

    /** Completed or Failed, with its end time, records out, the error (a JSON object) and a status line. */
    void ended(long stepExecutionId, String status, Long recordsOut, String errorJson, String message);

    /** Skip or Interrupt: a step that did not run, and why. */
    void notRun(long stepExecutionId, String status, String message);

    void log(long stepExecutionId, int lineNo, String level, String message);

    long dataset(long stepExecutionId, String name, String storageKey, long rows, String columnsJson, Instant expiresAt);

    List<StepRow> stepsOfRun(long jobQueueId);

    Optional<StepRow> stepById(long stepExecutionId);

    List<LogLine> logOf(long stepExecutionId);

    List<DatasetRow> datasetsOfRun(long jobQueueId);

    /**
     * One dataset by its id with what a download needs -- its run, step, key and expiry -- as the caller's session sees
     * it (row-level security); empty when there is none.
     */
    Optional<DatasetFile> datasetById(long runDatasetId);

    /** Records a file a step wrote in the run's manifest (run_output): once per step execution, a later record replacing it. */
    void output(long stepExecutionId, RunOutput output, Long runDatasetId, Instant expiresAt);

    /** The run's manifest, every attempt, in step order. */
    List<OutputRow> outputsOfRun(long jobQueueId);

    /** The manifest row that named this dataset, if one did: what is left of a file dataset after the sweep removed it. */
    Optional<OutputRow> outputOfDataset(long runDatasetId);

    /**
     * MIG-334: the manifest row the customer API names by this file id, as the caller's session sees it (row-level
     * security); empty when there is none.
     */
    default Optional<OutputRow> outputByFileId(String fileId) {
        return Optional.empty();
    }

    /**
     * MIG-334: the file id of a manifest row, given now when it has none yet (a row recorded before file ids); null when the
     * row is not there for this session.
     */
    default String fileIdOf(long runOutputId) {
        return null;
    }

    /**
     * MIG-345: a step of a resumed attempt that is not run again -- Completed, its records the earlier step's, reused_from
     * naming it, no times (it did not run now) -- with a status line saying whose output it is.
     */
    default void reused(long stepExecutionId, long fromStepExecutionId, Long recordsIn, Long recordsOut, String message) {
        throw new UnsupportedOperationException("This step store does not record reused steps.");
    }

    /** MIG-345: what a step read of a bucket object, fingerprinted on the way (run_input_file). */
    default void inputRead(long stepExecutionId, InputDigests.Read read) {
    }

    /** MIG-345: the objects these steps read, by step execution; a step that read none is left out. */
    default Map<Long, List<InputDigests.Read>> inputsOf(Collection<Long> stepExecutionIds) {
        return new HashMap<>();
    }

    /**
     * MIG-345: each step's output dataset (run_dataset "output") by step execution -- its key, rows and expiry -- or, when the
     * sweep removed it (retention_log), that it is gone. A step that wrote none (it passed its input on) is left out.
     */
    default Map<Long, KeptOutput> keptOutputsOf(Collection<Long> stepExecutionIds) {
        return new HashMap<>();
    }

    /** A step's output dataset as a resume reads it: never handed to a caller as is (it names a storage key). */
    final class KeptOutput {
        public long stepExecutionId;
        public String storageKey;
        public Long rows;
        public Instant expiresAt;
        /** The sweep removed it: past its retention. */
        public boolean removed;

        public KeptOutput() {
        }

        public KeptOutput(long stepExecutionId, String storageKey, Long rows, Instant expiresAt, boolean removed) {
            this.stepExecutionId = stepExecutionId;
            this.storageKey = storageKey;
            this.rows = rows;
            this.expiresAt = expiresAt;
            this.removed = removed;
        }

        /** Gone or past its expiry at {@code now}. */
        public boolean expiredAt(Instant now) {
            return this.removed || (this.expiresAt != null && !this.expiresAt.isAfter(now));
        }
    }

    /** A step as the plan names it. */
    final class Planned {
        public final int index;
        public final String key;
        public final String task;
        public final String onError;

        public Planned(int index, String key, String task, String onError) {
            this.index = index;
            this.key = key;
            this.task = task;
            this.onError = onError;
        }
    }

    /** One step_execution row, for the timeline. Times are the business wall clock, as every run time Core shows. */
    final class StepRow {
        public long stepExecutionId;
        public long jobQueueId;
        public int attempt;
        public int stepIndex;
        public String stepKey;
        public String taskCode;
        public String status;
        public LocalDateTime startedAt;
        public LocalDateTime endedAt;
        public Long durationMs;
        public Long recordsIn;
        public Long recordsOut;
        public int tries;
        public String onError;
        public String statusMessage;
        public String error;
        public Long pipelineDefinitionId;
        /** MIG-345: a step of a resumed attempt not run again: the earlier attempt's step whose output it is. */
        public Long reusedFrom;
    }

    final class LogLine {
        public int lineNo;
        public String level;
        public String message;
        public LocalDateTime loggedAt;
    }

    /** A dataset for a download: its run and step, name, key, columns and expiry. Never handed to a caller as is. */
    final class DatasetFile {
        public long runDatasetId;
        public long stepExecutionId;
        public long jobQueueId;
        public int attempt;
        public String stepKey;
        public String name;
        public String storageKey;
        public String columns;
        public Instant expiresAt;
    }

    /** One run_output row, with its step. */
    final class OutputRow {
        public long runOutputId;
        public long stepExecutionId;
        public long jobQueueId;
        public int attempt;
        public int stepIndex;
        public String stepKey;
        public String taskCode;
        public String kind;
        public String name;
        public String format;
        public Long rowCount;
        public Long byteCount;
        public Long runDatasetId;
        public String bucketAlias;
        public String objectKey;
        public Instant expiresAt;
        public LocalDateTime recordedAt;
        /** MIG-334: the file id the customer API names it by (a ULID); null until given. */
        public String fileId;
        /** MIG-334: hex sha256 of what the step wrote; null for a row recorded before. */
        public String sha256;
    }

    /** A dataset a step wrote -- never its storage key, which is the platform's. */
    final class DatasetRow {
        public long runDatasetId;
        public long stepExecutionId;
        public String name;
        public Long rowCount;
        public String columns;
        public LocalDateTime expiresAt;
    }
}
