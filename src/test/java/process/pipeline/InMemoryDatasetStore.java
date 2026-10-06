package process.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@link DatasetStore} in memory, for the engine's unit tests. */
public class InMemoryDatasetStore implements DatasetStore {

    public final Map<String, Dataset> stored = new LinkedHashMap<>();
    public final Map<String, byte[]> files = new LinkedHashMap<>();
    public int reads;
    public RuntimeException failWrites;

    @Override
    public synchronized void write(String storageKey, Dataset dataset) {
        if (this.failWrites != null) {
            throw this.failWrites;
        }
        this.stored.put(storageKey, dataset);
    }

    @Override
    public synchronized Dataset read(String storageKey) {
        this.reads++;
        Dataset dataset = this.stored.get(storageKey);
        if (dataset == null) {
            throw new IllegalStateException("no dataset " + storageKey);
        }
        return dataset;
    }

    @Override
    public synchronized void writeFile(String storageKey, byte[] content) {
        this.files.put(storageKey, content);
    }

    @Override
    public synchronized byte[] readFile(String storageKey) {
        byte[] content = this.files.get(storageKey);
        if (content == null) {
            throw new IllegalStateException("no file " + storageKey);
        }
        return content;
    }

    @Override
    public synchronized void delete(String storageKey) {
        this.stored.remove(storageKey);
        this.files.remove(storageKey);
    }
}
