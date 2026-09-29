package process.pipeline;

import java.util.List;
import java.util.Map;

/**
 * What a step can run (MIG-230): a task registered under a code, which a definition's step names in {@code task}.
 * The Task Registry (MIG-231) adds the reusable tasks -- read, validate, transform, filter, join, enrich, aggregate,
 * save, write, notify -- as more of these; the engine needs nothing else from them.
 *
 * A task is handed its input and its config and returns its output. It does not retry, time itself out or decide what
 * its failure means: the engine does all three from the step's definition. It may throw: that is a failed try, and the
 * exception's message is what the step's log and error say. It should notice an interrupt (a timeout cancels the try
 * by interrupting its thread).
 */
public interface StepTask {

    /** The code a definition names ({@code task: sample}): lower case, stable, never reused. */
    String code();

    /** One line for the console's "Add step" list. */
    String description();

    /**
     * Whether the step engine runs this task. Only {@code legacy} says no: it is today's path (the worker), which
     * keeps running exactly as it did.
     */
    default boolean runsInEngine() {
        return true;
    }

    /**
     * What is wrong with this step's config, each problem at a path relative to the config ({@code "rows[0]"}, or
     * {@code "$"} for the config as a whole). Empty when it is fine. Unknown keys are problems.
     */
    List<DefinitionProblem> check(Map<String, Object> config);

    /** Runs one try of the step. */
    StepResult run(StepContext context) throws Exception;
}
