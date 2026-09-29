package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.StepTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows written in the definition itself (MIG-230): {@code config.rows}, a list of objects of plain values. What the
 * console's "Test with sample" runs a pipeline on (MIG-249), and the smallest read step there is, so a pipeline can be
 * built and run end to end before a real source is attached. The rows replace the step's input.
 */
@Component
public class SampleStepTask implements StepTask {

    public static final int MAX_ROWS = 1000;

    @Override
    public String code() {
        return "sample";
    }

    @Override
    public String description() {
        return "Rows written in the step itself: a sample to build and test a pipeline on.";
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "rows");
        Object rows = config.get("rows");
        if (!(rows instanceof List)) {
            problems.add(new DefinitionProblem("rows", "a list of rows (objects) is required"));
            return problems;
        }
        List<?> list = (List<?>) rows;
        if (list.size() > MAX_ROWS) {
            problems.add(new DefinitionProblem("rows", String.format("at most %d rows; found %d", MAX_ROWS, list.size())));
        }
        for (int i = 0; i < list.size(); i++) {
            Object row = list.get(i);
            if (!(row instanceof Map)) {
                problems.add(new DefinitionProblem("rows[" + i + "]", "a row is an object of column: value"));
                continue;
            }
            for (Map.Entry<?, ?> cell : ((Map<?, ?>) row).entrySet()) {
                if (!Configs.isScalar(cell.getValue())) {
                    problems.add(new DefinitionProblem("rows[" + i + "]." + cell.getKey(), "a value is text, a number, true/false or null"));
                }
            }
        }
        return problems;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult run(StepContext context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : (List<Object>) context.config().get("rows")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<Object, Object>) row).forEach((column, value) -> copy.put(String.valueOf(column), value));
            rows.add(copy);
        }
        context.log(String.format("%d sample row(s).", rows.size()));
        return StepResult.of(Dataset.of(rows));
    }
}
