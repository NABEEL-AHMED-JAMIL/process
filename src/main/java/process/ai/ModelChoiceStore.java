package process.ai;

import java.util.List;

/**
 * Core's rows for the AI model choice (V182): the schedule's setting on source_job and each run's AI steps in
 * run_ai_step. A run's own "Run with..." rides on the JobQueue entity, written with the run.
 */
public interface ModelChoiceStore {

    /**
     * Sets the schedule's model per step (a {@link ModelProfiles#write} value, null for none) on the job, in its own
     * workspace only. Returns how many rows it changed: 0 means no such job in that workspace.
     */
    int saveScheduleProfiles(Long jobId, Long tenantId, String profiles, Long updatedBy);

    /** The schedule's setting as stored, or null; read from the database, never from a cached entity. */
    String scheduleProfiles(Long jobId, Long tenantId);

    /** Writes each step of an attempt, replacing what an earlier preparation of the same attempt wrote. */
    void recordSteps(List<RunAiStep> steps);

    /** Every AI step recorded for the run, attempts in order, steps in the order they were prepared. */
    List<RunAiStep> stepsOfRun(Long jobQueueId);
}
