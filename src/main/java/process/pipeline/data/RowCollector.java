package process.pipeline.data;

import process.pipeline.Dataset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rows gathered one at a time, held to {@link Limits} as they come: a read that would pass a bound fails at the row
 * that passes it, not after the whole source is in memory. {@code maxRows} is the step's own smaller ask: reaching it
 * is not a failure, and {@link #full()} says to stop reading.
 */
public final class RowCollector {

    private final String what;
    private final int maxRows;
    private final Set<String> columns = new LinkedHashSet<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();

    public RowCollector(String what, Integer maxRows) {
        this.what = what;
        this.maxRows = maxRows == null ? Limits.MAX_ROWS : Math.min(maxRows, Limits.MAX_ROWS);
    }

    /** Adds a row; an exception when it would take the dataset past a bound the step did not ask for. */
    public void add(Map<String, Object> row) {
        if (this.rows.size() >= this.maxRows) {
            if (this.maxRows >= Limits.MAX_ROWS) {
                throw new IllegalStateException(String.format("%s has more than %,d rows, the most a step holds.", this.what,
                    Limits.MAX_ROWS));
            }
            return;
        }
        this.columns.addAll(row.keySet());
        Limits.requireShape(this.rows.size() + 1, this.columns.size(), this.what);
        this.rows.add(row);
    }

    /** Whether the step's own maxRows is reached (a smaller ask than the bound): stop reading. */
    public boolean full() {
        return this.maxRows < Limits.MAX_ROWS && this.rows.size() >= this.maxRows;
    }

    public int size() {
        return this.rows.size();
    }

    public Dataset toDataset() {
        return new Dataset(new ArrayList<>(this.columns), this.rows);
    }

    public Dataset toDataset(List<String> columnOrder) {
        Set<String> ordered = new LinkedHashSet<>(columnOrder);
        ordered.addAll(this.columns);
        return new Dataset(new ArrayList<>(ordered), this.rows);
    }
}
