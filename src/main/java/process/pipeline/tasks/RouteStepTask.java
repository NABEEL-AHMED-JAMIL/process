package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.Routes;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Route (MIG-450, owner 2026-11-02): sends each row down the first branch whose conditions it meets -- conditions built as
 * Filter's (column, operator, value; all or any of them) -- else down Otherwise. Its output is every row with the branch
 * it took in a column ({@code column}, "branch" by default); the engine keeps each branch's rows apart as
 * "&lt;route&gt;.&lt;branch&gt;", which the first step of a branch reads (Routes). A branch with no rows still exists: its steps run on
 * nothing. 1 to 9 condition branches and Otherwise: 2 to 10 in all.
 */
@Component
public class RouteStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX_BRANCHES = 9;
    static final String NAME_PATTERN = "^[a-z][a-z0-9_]{0,39}$";

    static final TaskSpec SPEC = TaskSpec.builder(Routes.ROUTE, "Route (if / otherwise)", TaskKind.PROCESS)
        .description("Sends each row down the first branch whose conditions it meets, else down Otherwise; each branch is its own "
            + "chain of steps, and a Merge brings them together again.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("Every row, with the branch it took in the branch column; each branch's rows are read as "
            + "<route>.<branch>."))
        .config(JsonSchema.object()
            .required("branches", JsonSchema.array(JsonSchema.object()
                .required("name", JsonSchema.string().pattern(NAME_PATTERN).title("Branch name")
                    .description("Lowercase letters, digits and _: urgent, needs_review."))
                .property("match", JsonSchema.string().enumOf("all", "any").title("A row takes it when it meets").defaultValue("all"))
                .required("conditions", JsonSchema.array(FilterStepTask.conditionSchema()).minItems(1).maxItems(50).title("Conditions")))
                .minItems(1).maxItems(MAX_BRANCHES).title("Branches")
                .description("In order: a row goes down the first branch it meets."))
            .property("otherwise", JsonSchema.string().pattern(NAME_PATTERN).title("Otherwise branch")
                .defaultValue(Routes.DEFAULT_OTHERWISE).description("Where every row that meets no branch goes."))
            .property("column", JsonSchema.string().pattern("^[A-Za-z_][A-Za-z0-9_]{0,63}$").title("Branch column")
                .defaultValue(Routes.DEFAULT_COLUMN).description("The column each row's branch is written to.")))
        .backing(TaskSpec.CORE)
        .aiToolName("route_rows")
        .items(ItemRole.PASS)
        .build();

    public RouteStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        List<Map<String, Object>> branches = Configs.objects(config, "branches");
        Set<String> names = new HashSet<>();
        String otherwise = Configs.text(config, "otherwise", Routes.DEFAULT_OTHERWISE);
        for (int i = 0; i < branches.size(); i++) {
            String name = Configs.text(branches.get(i), "name", "");
            if (!names.add(name.toLowerCase(Locale.ROOT)) || name.equalsIgnoreCase(otherwise)) {
                problems.add(new DefinitionProblem("branches[" + i + "].name", String.format("'%s' is already a branch of this route", name)));
            }
            problems.addAll(FilterStepTask.conditionProblems(Configs.objects(branches.get(i), "conditions"), "branches[" + i + "]."));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        List<Map<String, Object>> branches = Configs.objects(config, "branches");
        String otherwise = Configs.text(config, "otherwise", Routes.DEFAULT_OTHERWISE);
        String column = Configs.text(config, "column", Routes.DEFAULT_COLUMN);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> branch : branches) {
            counts.put(Configs.text(branch, "name", ""), 0L);
        }
        counts.put(otherwise, 0L);
        RowSink out = context.output();
        boolean replaced = false;
        try (RowSource input = context.openInput()) {
            List<String> columns = new ArrayList<>(input.columns());
            replaced = columns.contains(column);
            if (!replaced) {
                columns.add(column);
            }
            out.declare(columns);
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Route was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    String took = otherwise;
                    for (Map<String, Object> branch : branches) {
                        if (FilterStepTask.matches(row, Configs.objects(branch, "conditions"),
                            "any".equals(Configs.text(branch, "match", "all")))) {
                            took = Configs.text(branch, "name", "");
                            break;
                        }
                    }
                    counts.merge(took, 1L, Long::sum);
                    Map<String, Object> routed = new LinkedHashMap<>(row);
                    routed.put(column, took);
                    out.add(routed);
                }
            }
        }
        if (replaced) {
            context.warn(String.format("The input already had a column '%s'; it now holds each row's branch.", column));
        }
        String split = words(counts);
        context.log(String.format("%,d row(s) routed: %s.", out.size(), split));
        return StepResult.streamed(out.size()).withNote(split);
    }

    /** "urgent 12 · otherwise 88": how the run page says a route's split. */
    static String words(Map<String, Long> counts) {
        StringBuilder line = new StringBuilder();
        counts.forEach((name, n) -> line.append(line.length() == 0 ? "" : " · ").append(name).append(' ').append(String.format("%,d", n)));
        return line.toString();
    }
}
