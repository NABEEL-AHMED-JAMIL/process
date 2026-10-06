package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.data.Expression;
import process.pipeline.data.Limits;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compute (MIG-255): adds columns worked out from each row by formula (see {@link Expression}: arithmetic, comparisons,
 * if, round, coalesce, days_between...). Formulas run in order, so a later one reads an earlier one's column. A formula
 * that cannot be worked out for a row -- a text used as a number -- fails the step naming the row, or with
 * {@code nullOnError} leaves that column empty. Formulas are parsed when the definition is saved.
 */
@Component
public class ComputeStepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("compute", "Compute", TaskKind.PROCESS)
        .description("Adds columns worked out from each row by formula: arithmetic, comparisons, if, round, coalesce, dates.")
        .input(TaskSpec.rows("The rows to compute on."))
        .output(TaskSpec.rows("The input's columns, then each formula's column (a formula may replace a column)."))
        .config(JsonSchema.object()
            .required("formulas", JsonSchema.array(JsonSchema.object()
                .required("target", JsonSchema.string().minLength(1).maxLength(128).title("Column"))
                .required("expression", JsonSchema.string().minLength(1).maxLength(1000).format("expression").title("Formula")
                    .description("e.g. round(length_cm * width_cm, 1), or if(change_pct <= -10, 'improving', 'stable')."))
                .property("nullOnError", JsonSchema.bool().title("Empty when it cannot be worked out").defaultValue(false)))
                .minItems(1).maxItems(50).title("Formulas")))
        .backing(TaskSpec.CORE)
        .aiToolName("compute_columns")
        .build();

    public ComputeStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        List<Map<String, Object>> formulas = Configs.objects(config, "formulas");
        for (int i = 0; i < formulas.size(); i++) {
            Map<String, Object> formula = formulas.get(i);
            try {
                Expression.parse(Configs.text(formula, "expression", ""));
            } catch (IllegalArgumentException bad) {
                problems.add(new DefinitionProblem("formulas[" + i + "].expression", bad.getMessage()));
            }
            String target = Configs.text(formula, "target", "");
            if (!targets.add(target)) {
                problems.add(new DefinitionProblem("formulas[" + i + "].target", String.format("'%s' is already made by an earlier formula", target)));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) {
        List<Map<String, Object>> formulas = Configs.objects(context.config(), "formulas");
        List<String> targets = new ArrayList<>();
        List<Expression> expressions = new ArrayList<>();
        List<Boolean> lenient = new ArrayList<>();
        for (Map<String, Object> formula : formulas) {
            targets.add(Configs.text(formula, "target", ""));
            expressions.add(Expression.parse(Configs.text(formula, "expression", "")));
            lenient.add(Configs.bool(formula, "nullOnError", false));
        }
        Dataset input = context.input();
        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        columns.addAll(targets);
        Limits.requireShape(input.size(), columns.size(), "The computed rows");

        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int emptied = 0;
        for (int r = 0; r < input.size(); r++) {
            Map<String, Object> row = new LinkedHashMap<>(input.getRows().get(r));
            for (int f = 0; f < expressions.size(); f++) {
                Object value;
                try {
                    value = expressions.get(f).evaluate(row);
                } catch (IllegalArgumentException cannot) {
                    if (!lenient.get(f)) {
                        throw new IllegalStateException(String.format("Row %d, %s: %s", r + 1, targets.get(f), cannot.getMessage()));
                    }
                    emptied++;
                    value = null;
                }
                row.put(targets.get(f), value);
            }
            rows.add(row);
        }
        context.log(String.format("%d formula(s) on %d row(s).", expressions.size(), input.size()));
        if (emptied > 0) {
            context.warn(String.format("%d value(s) could not be worked out and are empty.", emptied));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }
}
