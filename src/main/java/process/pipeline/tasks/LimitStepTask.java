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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Limit / top N (MIG-365): keeps {@code rows} rows after skipping {@code offset}.
 *
 * <ul>
 *   <li>Without an order: the first rows in input order. It streams, and stops reading its input as soon as it has them:
 *       the rest of the input is never read (the step's log says how much it read).</li>
 *   <li>With {@code by}: the top N in that order ({@link RowOrder}, as Sort orders) -- it reads every row but holds only
 *       offset + rows of them, the best so far, in a bounded heap. When even those pass the run's memory budget it sorts
 *       the input on disk instead ({@link ExternalSort}) and stops once it has them. Equal rows keep their input order.</li>
 * </ul>
 */
@Component
public class LimitStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX = Integer.MAX_VALUE;

    static final TaskSpec SPEC = TaskSpec.builder("limit", "Limit / top N", TaskKind.PROCESS)
        .description("Keeps the first N rows (stops reading there), or the top N by an order, optionally after skipping some.")
        .input(TaskSpec.rows("The rows to take from."))
        .output(TaskSpec.rows("At most N of the rows, columns unchanged."))
        .config(JsonSchema.object()
            .required("rows", JsonSchema.integer().minimum(1).maximum(MAX).title("Rows to keep")
                .description("How many rows the step keeps."))
            .property("offset", JsonSchema.integer().minimum(0).maximum(MAX).title("Skip first").defaultValue(0)
                .description("Rows skipped before those kept (for pages: offset 100, rows 100 is the second hundred)."))
            .property("by", Orders.schema("Top N by", "Empty: the first rows in their order, and the rest of the input is not read. "
                + "Set: the top rows in this order (desc for the largest first), reading every row but holding only those.")))
        .backing(TaskSpec.CORE)
        .aiToolName("limit_rows")
        .build();

    public LimitStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        return Orders.check(config, "by");
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        long keep = Configs.longValue(config, "rows") == null ? 1 : Configs.longValue(config, "rows");
        long offset = Configs.longValue(config, "offset") == null ? 0 : Configs.longValue(config, "offset");
        RowOrder order = Orders.of(config, "by");
        RowSink out = context.output();
        if (order == null) {
            return this.first(context, keep, offset, out);
        }
        return this.top(context, order, keep, offset, out);
    }

    /** The first rows, in order; reading stops once they are in hand. */
    private StepResult first(StreamContext context, long keep, long offset, RowSink out) throws Exception {
        long read = 0;
        long size;
        try (RowSource input = context.openInput()) {
            size = input.size();
            out.declare(input.columns());
            long end = offset + keep;
            batches:
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Limit was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    read++;
                    if (read > offset) {
                        out.add(row);
                    }
                    if (read >= end) {
                        break batches;
                    }
                }
            }
        }
        context.log(String.format("Kept %,d row(s)%s of %,d; read %,d row(s)%s.", out.size(),
            offset > 0 ? String.format(" after skipping %,d", offset) : "", size, read,
            read < size ? String.format(", the other %,d were not read", size - read) : ""));
        return StepResult.streamed(out.size());
    }

    /** The top rows by the order: a bounded heap of the best so far, or a sort on disk when even those do not fit. */
    private StepResult top(StreamContext context, RowOrder order, long keep, long offset, RowSink out) throws Exception {
        long wanted = keep + offset;
        RunMemory memory = context.memory();
        List<String> columns;
        long size;
        // The worst of the best so far on top, so a better row replaces it; on a tie the later row is the worse.
        PriorityQueue<Held> best = new PriorityQueue<>((a, b) -> -compare(order, a, b));
        long held = 0;
        boolean fits = true;
        try (RowSource input = context.openInput()) {
            columns = input.columns();
            size = input.size();
            Orders.requireColumns(order, columns, "The input");
            out.declare(columns);
            double perRow = -1;
            long seq = 0;
            reading:
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Limit was stopped.");
                }
                if (perRow < 0) {
                    perRow = (double) RunMemory.estimate(batch) / batch.size() + 48 + 32L * order.by().size();
                }
                for (Map<String, Object> row : batch) {
                    Held candidate = new Held(row, order.keyOf(row), seq++);
                    if (best.size() < wanted) {
                        if (!memory.tryReserve((long) perRow)) {
                            fits = false;
                            break reading;
                        }
                        held += (long) perRow;
                        best.add(candidate);
                    } else if (compare(order, candidate, best.peek()) < 0) {
                        best.poll();
                        best.add(candidate);
                    }
                }
            }
            if (fits) {
                List<Held> sorted = new ArrayList<>(best);
                sorted.sort((a, b) -> compare(order, a, b));
                for (int i = (int) Math.min(offset, sorted.size()); i < sorted.size(); i++) {
                    out.add(sorted.get(i).row);
                }
            }
        } finally {
            memory.release(held);
            best.clear();
        }
        if (fits) {
            context.log(String.format("Kept the top %,d row(s)%s of %,d by %s, holding at most %,d at once.", out.size(),
                offset > 0 ? String.format(" after skipping %,d", offset) : "", size, Orders.describe(order), Math.min(wanted, size)));
            return StepResult.streamed(out.size());
        }
        // Too many to hold: sort the input on disk and stop once the top rows are out.
        long[] seen = {0};
        ExternalSort.Outcome outcome;
        try (RowSource input = context.openInput()) {
            outcome = ExternalSort.sort(input, order, memory, context::scratch, columns, row -> {
                seen[0]++;
                if (seen[0] > offset) {
                    out.add(row);
                }
                return seen[0] < wanted;
            });
        }
        context.log(String.format("Kept the top %,d row(s)%s of %,d by %s. %,d rows would not fit the run's memory budget (%s), so the "
            + "input was sorted on disk (%d run(s), %s) and the merge stopped at them.", out.size(),
            offset > 0 ? String.format(" after skipping %,d", offset) : "", size, Orders.describe(order), wanted,
            RunMemory.megabytes(memory.budget()), outcome.runs, RunMemory.megabytes(outcome.spilledBytes)));
        return StepResult.streamed(out.size());
    }

    private static int compare(RowOrder order, Held a, Held b) {
        int c = order.compare(a.keys, b.keys);
        return c != 0 ? c : Long.compare(a.seq, b.seq);
    }

    private static final class Held {
        final Map<String, Object> row;
        final RowOrder.Key[] keys;
        final long seq;

        Held(Map<String, Object> row, RowOrder.Key[] keys, long seq) {
            this.row = row;
            this.keys = keys;
            this.seq = seq;
        }
    }
}
