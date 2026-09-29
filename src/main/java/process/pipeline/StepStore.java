package process.pipeline;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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

    /** The definition an earlier attempt of this run followed, if one did: a retry follows the same one. */
    Optional<Long> pinnedDefinition(long jobQueueId);

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
    }

    final class LogLine {
        public int lineNo;
        public String level;
        public String message;
        public LocalDateTime loggedAt;
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
