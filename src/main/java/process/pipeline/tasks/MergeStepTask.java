package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Routes;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Merge (MIG-450): brings a Route's branches together again -- each branch's last output (or its own rows, when it has
 * no step), stacked in the route's order, matching columns by name as Append does (a column a branch lacks is empty).
 * Each row says which branch it came from in {@code column} (the route's branch column, "branch" by default). The steps
 * after a Merge are one line again.
 */
@Component
public class MergeStepTask extends RegisteredTask implements StreamingStepTask {

    static final TaskSpec SPEC = TaskSpec.builder(Routes.MERGE, "Merge branches", TaskKind.PROCESS)
        .description("Brings a Route's branches together again: every branch's rows stacked, with the branch each came from.")
        .input(TaskSpec.rows("Not read: the route's branches are."))
        .output(TaskSpec.rows("Every branch's rows, in the route's order; columns by name, and the branch column."))
        .config(JsonSchema.object()
            .required("route", JsonSchema.string().minLength(1).format("step").title("Route")
                .description("The Route step whose branches come together here."))
            .property("columns", JsonSchema.string().enumOf("all", "common").title("Columns").defaultValue("all")
                .description("all: every column any branch has (missing ones are empty); common: only the columns every branch has."))
            .property("column", JsonSchema.string().pattern("^[A-Za-z_][A-Za-z0-9_]{0,63}$").title("Branch column")
                .description("The column saying which branch each row came from; the route's own branch column when empty.")))
        .backing(TaskSpec.CORE)
        .aiToolName("merge_branches")
        .items(ItemRole.PASS)
        .build();

    public MergeStepTask() {
        super(SPEC);
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String routeKey = Configs.text(config, "route", "");
        List<Routes.Head> heads = context.branchHeads(routeKey);
        if (heads.isEmpty()) {
            throw new IllegalArgumentException(String.format("<%s> is not a Route step before this one.", routeKey));
        }
        List<String> reads = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Routes.Head head : heads) {
            reads.add(head.reads);
            labels.add(head.branch);
        }
        String column = Configs.text(config, "column", null);
        if (column == null) {
            column = heads.get(0).column;
        }
        boolean common = "common".equals(Configs.text(config, "columns", "all"));
        return AppendStepTask.stack(context, reads, labels, column, true, common, true, Collections.emptyMap(), "Merge");
    }
}
