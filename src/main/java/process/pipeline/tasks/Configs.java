package process.pipeline.tasks;

import process.pipeline.DefinitionProblem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The checks every task's config shares. */
final class Configs {

    private Configs() {
    }

    /** One problem per key the task does not know. */
    static void onlyKnownKeys(Map<String, Object> config, List<DefinitionProblem> problems, String... known) {
        Set<String> allowed = new HashSet<>(Arrays.asList(known));
        for (String key : config.keySet()) {
            if (!allowed.contains(key)) {
                problems.add(new DefinitionProblem(key, String.format("unknown setting '%s'", key)));
            }
        }
    }

    /** A value a row may hold: text, a number, true/false, or nothing. */
    static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
