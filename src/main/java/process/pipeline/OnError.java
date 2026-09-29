package process.pipeline;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What a step's failure does to its run, once the step's own retries are spent (MIG-230). The default is
 * {@link #FAIL}, as a pipeline's AI step already fails its run unless it says "continue" -- the same two words, plus
 * one.
 *
 * <ul>
 *   <li>{@link #FAIL} -- the run fails: the rest of the steps are Skip, and the run is reported Failed through the
 *       worker callback's own rules (NotifyServiceImpl), so the job's retry policy (BulkAction.scheduleRetry), its fail
 *       mail and the meter behave exactly as for a worker's failure.</li>
 *   <li>{@link #CONTINUE} -- the step is Failed and the run goes on. The next step reads the latest output made before
 *       the failed step (a step that names the failed step as its input fails too, having nothing to read). The run
 *       ends Completed, and its status line says how many steps failed.</li>
 *   <li>{@link #SKIP_REST} -- the step is Failed, the steps after it are Skip, and the run ends Completed: a guard that
 *       stops a pipeline early without making the run a failure (nothing to process today, say).</li>
 * </ul>
 */
public enum OnError {

    FAIL("fail"),
    CONTINUE("continue"),
    SKIP_REST("skip_rest");

    private final String word;

    OnError(String word) {
        this.word = word;
    }

    /** The spelling a definition, step_execution.on_error and the console use. */
    public String word() {
        return this.word;
    }

    public static Optional<OnError> of(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String trimmed = word.trim();
        return Arrays.stream(values()).filter(value -> value.word.equals(trimmed)).findFirst();
    }

    public static List<String> words() {
        return Arrays.stream(values()).map(OnError::word).collect(Collectors.toList());
    }
}
