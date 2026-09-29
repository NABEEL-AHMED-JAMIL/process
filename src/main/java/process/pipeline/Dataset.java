package process.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rows passed from one step to the next (MIG-230): named columns, and rows as column -> value. A step receives its
 * input and returns its output as one of these; between steps the engine keeps it in a {@link DatasetStore} and hands
 * the next step a reference (run_dataset), never the rows through the database.
 */
public final class Dataset {

    public static final Dataset EMPTY = new Dataset(Collections.emptyList(), Collections.emptyList());

    private final List<String> columns;
    private final List<Map<String, Object>> rows;

    public Dataset(List<String> columns, List<Map<String, Object>> rows) {
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        this.rows = Collections.unmodifiableList(copy);
    }

    /** Rows whose columns are every key they use, in the order first seen. */
    public static Dataset of(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }
        return new Dataset(new ArrayList<>(columns), rows);
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public int size() {
        return this.rows.size();
    }

    public boolean isEmpty() {
        return this.rows.isEmpty();
    }
}
