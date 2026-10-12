package process.pipeline.data;

import process.pipeline.Dataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A step's input as a stream of row batches (MIG-344): its columns and row count are known before the first row, and
 * {@link #next} hands out at most a batch at a time, so a step that streams holds one batch, not the table. Pull-based:
 * nothing is read before the step asks for it, which is all the backpressure a chain of streaming steps needs.
 *
 * Rows are column -> value maps, exactly as a {@link Dataset}'s rows are once read back from the dataset store: the
 * same keys in the same order, the same value types.
 */
public interface RowSource extends AutoCloseable {

    /** The rows a batch holds when nothing else says. */
    int DEFAULT_BATCH = 1024;

    List<String> columns();

    /** The number of rows, known before the first is read. */
    long size();

    /** The next batch (never empty), or null at the end. */
    List<Map<String, Object>> next() throws Exception;

    @Override
    void close() throws Exception;

    /** A dataset already in memory, in batches. */
    static RowSource of(Dataset dataset) {
        return of(dataset, DEFAULT_BATCH);
    }

    static RowSource of(Dataset dataset, int batch) {
        return new RowSource() {
            private int at;

            @Override
            public List<String> columns() {
                return dataset.getColumns();
            }

            @Override
            public long size() {
                return dataset.size();
            }

            @Override
            public List<Map<String, Object>> next() {
                if (this.at >= dataset.size()) {
                    return null;
                }
                int to = Math.min(dataset.size(), this.at + Math.max(1, batch));
                List<Map<String, Object>> rows = dataset.getRows().subList(this.at, to);
                this.at = to;
                return rows;
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * The whole source as a dataset, held to {@link Limits} first: for a task that needs its whole input in memory.
     * {@code what} names the input in the error.
     */
    static Dataset materialize(RowSource source, String what) throws Exception {
        if (source.size() > Limits.MAX_ROWS || source.size() * Math.max(1, source.columns().size()) > Limits.MAX_CELLS) {
            throw new IllegalStateException(String.format("%s has %,d rows x %d columns; a step that holds its whole input in memory "
                + "takes at most %,d rows and %,d cells. Put a filter, select or aggregate before it.", what, source.size(),
                source.columns().size(), Limits.MAX_ROWS, Limits.MAX_CELLS));
        }
        List<Map<String, Object>> rows = new ArrayList<>((int) Math.max(0, source.size()));
        for (List<Map<String, Object>> batch = source.next(); batch != null; batch = source.next()) {
            rows.addAll(batch);
        }
        return new Dataset(source.columns(), rows);
    }

    /** An empty source with these columns. */
    static RowSource empty(List<String> columns) {
        return of(new Dataset(columns == null ? Collections.<String>emptyList() : columns, Collections.<Map<String, Object>>emptyList()));
    }
}
