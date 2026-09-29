package process.pipeline;

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

    /** A line in the step's log (step_log), shown on the step in the console's timeline. */
    void log(String message);

    void warn(String message);
}
