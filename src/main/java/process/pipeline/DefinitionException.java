package process.pipeline;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** A definition that could not be read, or that does not validate: every problem, each at its path. */
public class DefinitionException extends Exception {

    private final List<DefinitionProblem> problems;

    public DefinitionException(List<DefinitionProblem> problems) {
        super(problems.stream().map(DefinitionProblem::toString).collect(Collectors.joining("; ")));
        this.problems = Collections.unmodifiableList(problems);
    }

    public DefinitionException(DefinitionProblem problem) {
        this(Collections.singletonList(problem));
    }

    public List<DefinitionProblem> getProblems() {
        return this.problems;
    }
}
