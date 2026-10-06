package process.pipeline.tasks;

import process.pipeline.DefinitionProblem;
import process.pipeline.StepTask;
import process.pipeline.registry.TaskSpec;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Task Registry built-in (MIG-231): its code and description are its entry's, its config is checked against the
 * entry's config schema by the validator, and {@link #check} adds only what a schema cannot say (a mapping's op needs
 * its source, say).
 */
abstract class RegisteredTask implements StepTask {

    private final TaskSpec spec;

    RegisteredTask(TaskSpec spec) {
        this.spec = spec;
    }

    @Override
    public final TaskSpec spec() {
        return this.spec;
    }

    @Override
    public final String code() {
        return this.spec.code();
    }

    @Override
    public final String description() {
        return this.spec.description();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        return Collections.emptyList();
    }
}
