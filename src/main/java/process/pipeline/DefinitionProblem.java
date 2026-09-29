package process.pipeline;

import java.util.Objects;

/**
 * One thing wrong with a pipeline definition, at its path ("steps[2].retry.maxAttempts"), so the console can point at
 * the step and the field (MIG-249: "validation errors point at the step").
 */
public final class DefinitionProblem {

    private final String path;
    private final String message;

    public DefinitionProblem(String path, String message) {
        this.path = path == null || path.isEmpty() ? "$" : path;
        this.message = message;
    }

    public String getPath() {
        return path;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return this.path + ": " + this.message;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DefinitionProblem)) {
            return false;
        }
        DefinitionProblem that = (DefinitionProblem) other;
        return this.path.equals(that.path) && Objects.equals(this.message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.path, this.message);
    }
}
