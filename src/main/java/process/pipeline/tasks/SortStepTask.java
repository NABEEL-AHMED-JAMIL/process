package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.ExternalSort;
import process.pipeline.data.RowOrder;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.List;
import java.util.Map;

/**
 * Sort (MIG-365): the rows in the order of one or more columns ({@link RowOrder}: numbers as numbers, then text; empty
 * values last unless asked first; equal rows keep their input order). A whole-table step that still holds only the run's
 * memory budget: past it, sorted runs spill to disk and are merged ({@link ExternalSort}), so a sort of any size streams
 * in and out. The step's log says whether it sorted in memory or how much it spilled.
 */
@Component
public class SortStepTask extends RegisteredTask implements StreamingStepTask {

    static final TaskSpec SPEC = TaskSpec.builder("sort", "Sort", TaskKind.PROCESS)
        .description("Orders the rows by one or more columns, ascending or descending; any number of rows, spilling to disk past "
            + "the run's memory budget.")
        .input(TaskSpec.rows("The rows to sort."))
        .output(TaskSpec.rows("The same rows and columns, in order."))
        .config(JsonSchema.object()
            .required("by", Orders.schema("Sort by", "The first column decides; the next breaks ties, and so on. Numbers sort as "
                + "numbers (also when a CSV holds them as text) before text; text sorts A to Z; ISO dates sort in date order. Rows "
                + "equal on every column keep their order.")))
        .backing(TaskSpec.CORE)
        .timeoutSeconds(1800)
        .aiToolName("sort_rows")
        .build();

    public SortStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        return Orders.check(config, "by");
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        RowOrder order = Orders.of(context.config(), "by");
        RowSink out = context.output();
        ExternalSort.Outcome outcome;
        try (RowSource input = context.openInput()) {
            Orders.requireColumns(order, input.columns(), "The input");
            out.declare(input.columns());
            outcome = ExternalSort.sort(input, order, context.memory(), context::scratch, input.columns(), row -> {
                out.add(row);
                return true;
            });
        }
        if (outcome.runs == 0) {
            context.log(String.format("Sorted %,d row(s) by %s, in memory.", outcome.rows, Orders.describe(order)));
        } else {
            context.log(String.format("Sorted %,d row(s) by %s: they passed the run's memory budget (%s), so %d sorted run(s) went to "
                + "disk (%s) and were merged in %d pass(es).", outcome.rows, Orders.describe(order),
                RunMemory.megabytes(context.memory().budget()), outcome.runs, RunMemory.megabytes(outcome.spilledBytes), outcome.passes));
        }
        return StepResult.streamed(out.size());
    }
}
