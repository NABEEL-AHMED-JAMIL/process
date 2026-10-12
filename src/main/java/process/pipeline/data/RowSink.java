package process.pipeline.data;

import process.pipeline.Dataset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a streaming step puts its output rows (MIG-344), one at a time: the engine's sink writes them to the step's
 * dataset file as they come, so the step never holds them.
 *
 * Columns follow {@link Dataset}'s two ways: a step that {@link #declare declares} its columns has exactly those (as a
 * task's {@code new Dataset(columns, rows)}); one that does not has every key its rows use, in the order first seen
 * (as {@link Dataset#of} and {@link RowCollector}).
 */
public interface RowSink {

    /** The output's columns, as a task would name them to {@code new Dataset(columns, rows)}. */
    void declare(Collection<String> columns);

    void add(Map<String, Object> row) throws Exception;

    /** Rows added so far. */
    long size();

    /** A sink that keeps the rows in memory -- the in-memory path of a streaming task, held to {@link Limits}. */
    static Collecting collecting(String what) {
        return new Collecting(what);
    }

    final class Collecting implements RowSink {
        private final String what;
        private List<String> declared;
        private final Set<String> seen = new LinkedHashSet<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();

        Collecting(String what) {
            this.what = what;
        }

        @Override
        public void declare(Collection<String> columns) {
            this.declared = new ArrayList<>(columns);
        }

        @Override
        public void add(Map<String, Object> row) {
            if (this.declared == null) {
                this.seen.addAll(row.keySet());
            }
            Limits.requireShape(this.rows.size() + 1L, this.declared != null ? this.declared.size() : this.seen.size(), this.what);
            this.rows.add(row);
        }

        @Override
        public long size() {
            return this.rows.size();
        }

        public Dataset toDataset() {
            return new Dataset(this.declared != null ? this.declared : new ArrayList<>(this.seen), this.rows);
        }
    }
}
