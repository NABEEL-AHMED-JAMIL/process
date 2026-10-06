package process.pipeline;

import process.pipeline.registry.TaskSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * The task's Task Registry entry (MIG-231): its name, kind, schemas -- the config schema the console builds the
     * step's form from and the validator checks the config against -- backing service, default retry and timeout,
     * required role, enabled default and AI tool name. Every built-in declares its own; the fallback is for tests.
     */
    default TaskSpec spec() {
        return TaskSpec.minimal(this.code(), this.description(), this.runsInEngine());
    }

    /**
     * Why the task cannot run here now -- the other service's side it calls is not there yet, or is switched off in
     * this Core's configuration -- or empty when it can. An unavailable task is listed, disabled, with this reason, and
     * cannot be added to a pipeline or run.
     */
    default Optional<String> unavailable() {
        return Optional.empty();
    }

    /**
     * What is wrong with this step's config beyond its config schema (which the validator checks first, and only when
     * that holds is this asked), each problem at a path relative to the config ({@code "rows[0]"}, or {@code "$"} for
     * the config as a whole). Empty when it is fine.
     */
    List<DefinitionProblem> check(Map<String, Object> config);

    /** Runs one try of the step. */
    StepResult run(StepContext context) throws Exception;
}
