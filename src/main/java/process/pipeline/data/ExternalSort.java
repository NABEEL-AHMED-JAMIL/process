package process.pipeline.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;

/**
 * Sorts a step's rows within the run's memory budget (MIG-365): a stable external merge sort over Core's own row file.
 *
 * <ol>
 *   <li>Rows are read a batch at a time and kept, each with its sort key worked out once ({@link RowOrder#keyOf}), while
 *       the run's budget ({@link RunMemory#tryReserve}) has room. When everything fits, they are sorted in memory and
 *       handed on: no file is written.</li>
 *   <li>When the budget says no, what is held is sorted and written to a run file ({@link RowsFile}) in the try's
 *       scratch directory, its memory given back, and reading goes on. Each run holds consecutive input rows, so
 *       merging the runs in order, run before run on a tie, keeps the input order of equal rows.</li>
 *   <li>The runs are merged {@value #FAN_IN} at a time (more passes for more runs), each run read a small batch at a
 *       time, and the merged rows handed on one by one.</li>
 * </ol>
 *
 * Why not DuckDB (ADR-026 kept it in reserve for sort): handing a million Java rows to DuckDB and back costs more than
 * this whole sort does (ADR-026 decision 2: 8.3 s per 1M rows for the appender and JDBC against about 1-2 s here), its
 * memory is native and outside the run's budget, and its order of mixed numbers and text is not the engine's. This sort
 * is exact by {@link RowOrder} and bounded by the budget; its spill is the same row file the aggregate spills to.
 *
 * Run files are removed when the sort ends, however it ends; the try's scratch directory goes with the try.
 */
public final class ExternalSort {

    /** How many runs one merge reads at once. */
    public static final int FAN_IN = 64;
    /** Rows each run hands the merge at a time. */
    static final int MERGE_BATCH = 256;
    /** What a held row costs beyond the row itself: its entry and its keys. */
    static final int ENTRY_BYTES = 48;

    /** Where sorted rows go, in order; false to stop (Limit has what it needs). */
    public interface Emit {
        boolean row(Map<String, Object> row) throws Exception;
    }

    /** What a sort did, for the step's log. */
    public static final class Outcome {
        public long rows;
        public int runs;
        public int passes;
        public long spilledBytes;
        public boolean stoppedEarly;
    }

    private ExternalSort() {
    }

    /**
     * Sorts the source's rows and hands them to {@code emit} in order.
     *
     * @param columns the columns run files declare (the input's)
     * @param scratch the try's scratch directory, asked for only when a run has to be written
     */
    public static Outcome sort(RowSource input, RowOrder order, RunMemory memory, Callable<Path> scratch, Collection<String> columns,
                               Emit emit) throws Exception {
        Outcome outcome = new Outcome();
        List<Entry> chunk = new ArrayList<>();
        List<Path> runs = new ArrayList<>();
        long held = 0;
        double perRow = -1;
        try {
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("The sort was stopped.");
                }
                if (perRow < 0) {
                    perRow = (double) RunMemory.estimate(batch) / batch.size() + ENTRY_BYTES + 32L * order.by().size();
                }
                long need = (long) (perRow * batch.size());
                if (!memory.tryReserve(need)) {
                    if (!chunk.isEmpty()) {
                        runs.add(writeRun(chunk, order, scratch.call(), runs.size(), columns, memory, outcome));
                        memory.release(held);
                        held = 0;
                        chunk = new ArrayList<>();
                    }
                    // A batch is always held, whatever the budget: it is what the step has in hand.
                    memory.reserve(need);
                }
                held += need;
                for (Map<String, Object> row : batch) {
                    chunk.add(new Entry(row, order.keyOf(row)));
                }
            }
            if (runs.isEmpty()) {
                chunk.sort((a, b) -> order.compare(a.keys, b.keys));
                for (Entry entry : chunk) {
                    outcome.rows++;
                    if (!emit.row(entry.row)) {
                        outcome.stoppedEarly = true;
                        break;
                    }
                }
                return outcome;
            }
            if (!chunk.isEmpty()) {
                runs.add(writeRun(chunk, order, scratch.call(), runs.size(), columns, memory, outcome));
            }
            memory.release(held);
            held = 0;
            chunk = null;
            outcome.runs = runs.size();
            outcome.passes = 1;
            Path dir = runs.get(0).getParent();
            int named = runs.size();
            while (runs.size() > FAN_IN) {
                List<Path> next = new ArrayList<>();
                for (int from = 0; from < runs.size(); from += FAN_IN) {
                    List<Path> group = runs.subList(from, Math.min(runs.size(), from + FAN_IN));
                    if (group.size() == 1) {
                        next.add(group.get(0));
                        continue;
                    }
                    Path merged = dir.resolve("sort-run-" + (named++) + ".rows");
                    RowsFile.Writer out = RowsFile.create(merged);
                    try {
                        out.declare(columns);
                        merge(group, order, memory, row -> {
                            out.add(row);
                            return true;
                        }, outcome, false);
                        long bytes = out.commit();
                        memory.spilled(bytes);
                        outcome.spilledBytes += bytes;
                    } finally {
                        out.abort();
                    }
                    for (Path done : group) {
                        Files.deleteIfExists(done);
                    }
                    next.add(merged);
                }
                runs = next;
                outcome.passes++;
            }
            merge(runs, order, memory, emit, outcome, true);
            return outcome;
        } finally {
            memory.release(held);
            for (Path run : runs) {
                Files.deleteIfExists(run);
            }
        }
    }

    private static Path writeRun(List<Entry> chunk, RowOrder order, Path dir, int number, Collection<String> columns, RunMemory memory,
                                 Outcome outcome) throws Exception {
        chunk.sort((a, b) -> order.compare(a.keys, b.keys));
        Path run = dir.resolve("sort-run-" + number + ".rows");
        RowsFile.Writer out = RowsFile.create(run);
        try {
            out.declare(columns);
            for (Entry entry : chunk) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("The sort was stopped.");
                }
                out.add(entry.row);
            }
            long bytes = out.commit();
            memory.spilled(bytes);
            outcome.spilledBytes += bytes;
            return run;
        } finally {
            out.abort();
        }
    }

    /** Merges sorted runs into {@code emit}: the smallest current row first, the earlier run first on a tie. */
    private static void merge(List<Path> runs, RowOrder order, RunMemory memory, Emit emit, Outcome outcome, boolean counted)
        throws Exception {
        PriorityQueue<Cursor> queue = new PriorityQueue<>(runs.size(), (a, b) -> {
            int c = order.compare(a.keys, b.keys);
            return c != 0 ? c : Integer.compare(a.run, b.run);
        });
        List<Cursor> open = new ArrayList<>();
        long buffers = 0;
        try {
            for (int i = 0; i < runs.size(); i++) {
                Cursor cursor = new Cursor(RowsFile.open(runs.get(i), MERGE_BATCH), i, order);
                open.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            if (!open.isEmpty() && !queue.isEmpty()) {
                // The merge holds a small batch of each run: counted, so the step's log says what it held.
                buffers = RunMemory.estimate(queue.peek().row) * MERGE_BATCH * runs.size();
                memory.reserve(buffers);
            }
            long n = 0;
            while (!queue.isEmpty()) {
                if ((++n & 1023) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("The sort was stopped.");
                }
                Cursor smallest = queue.poll();
                if (counted) {
                    outcome.rows++;
                }
                if (!emit.row(smallest.row)) {
                    outcome.stoppedEarly = true;
                    return;
                }
                if (smallest.advance()) {
                    queue.add(smallest);
                }
            }
        } finally {
            memory.release(buffers);
            for (Cursor cursor : open) {
                cursor.close();
            }
        }
    }

    private static final class Entry {
        final Map<String, Object> row;
        final RowOrder.Key[] keys;

        Entry(Map<String, Object> row, RowOrder.Key[] keys) {
            this.row = row;
            this.keys = keys;
        }
    }

    /** One run being merged: its current row and key. */
    private static final class Cursor {
        private final RowSource source;
        final int run;
        private final RowOrder order;
        private List<Map<String, Object>> batch;
        private int at;
        Map<String, Object> row;
        RowOrder.Key[] keys;

        Cursor(RowSource source, int run, RowOrder order) {
            this.source = source;
            this.run = run;
            this.order = order;
        }

        boolean advance() throws Exception {
            if (this.batch == null || this.at >= this.batch.size()) {
                this.batch = this.source.next();
                this.at = 0;
                if (this.batch == null) {
                    this.row = null;
                    return false;
                }
            }
            this.row = this.batch.get(this.at++);
            this.keys = this.order.keyOf(this.row);
            return true;
        }

        void close() {
            try {
                this.source.close();
            } catch (Exception ignored) {
                // closing a run file that is being deleted
            }
        }
    }
}
